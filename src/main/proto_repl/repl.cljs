(ns proto-repl.repl
  (:require [clojure.string :as str]
            [clojure.core.async :as async]
            ["atom" :refer [Emitter]]
            [proto-repl.utils :refer [edn->display-tree
                                      get-config
                                      get-keybindings
                                      obj->map
                                      pretty-edn]]
            [proto-repl.ink :as ink]
            [proto-repl.views.repl-view :as rv]
            [proto-repl.views.ink-repl-view :refer [make-ink-repl-view]]
            [proto-repl.repl-client.nrepl-client :as nrepl]
            [proto-repl.macros :refer-macros [go-try-log dochan!]]))

(def ^:private TreeView (js/require "../lib/tree-view"))
(def ^:private Spinner (js/require "../lib/load-widget"))


(defprotocol Repl
  (clear [this])
  (execute-code* [this code options])
  (eval-and-display [this options])
  (execute-entered-text [this])
  (exit [this])
  (get-type [this])
  (inline-result-handler [this result options])
  (interrupt [this])
  (make-inline-handler [this editor range value->tree])
  (on-did-close [this callback])
  (on-did-start [this callback])
  (on-did-stop [this callback])
  (running? [this])
  (start-remote-repl-connection [this {:keys [host port]}])

  (doc [this text])
  (info [this text])
  (stderr [this text])
  (stdout [this text]))

(defn execute-code
  ([this code] (execute-code* this code {}))
  ([this code options] (execute-code* this code options)))


(def ^:private repl-help-text
  "REPL Instructions

Code can be entered at the bottom and executed by pressing shift+enter.

Try it now by typing (+ 1 1) in the bottom section and pressing shift+enter.

Working in another Clojure file and sending forms to the REPL is the most efficient way to work. Use the following key bindings to send code to the REPL. See the settings for more keybindings.

ctrl-alt-, then b
Execute block. Finds the block of Clojure code your cursor is in and executes that.

ctrl-alt-, s

Executes the selection. Sends the selected text to the REPL.

You can disable this help text in the settings.")

;; ReplImpl "private" methods

(defn- handle-repl-started [this]
  (-> this :emitter (.emit "proto-repl-repl:start")))

(defn- handle-repl-stopped [this]
  (-> this :emitter (.emit "proto-repl-repl:stop")))

(defn- build-tree-view [[head button-options & children]]
  (let [button-options (or button-options {})
        child-views (map #(if (vector? %) (build-tree-view %) (.leafView TreeView % #js {}))
                         children)]
    (if (seq child-views)
      (.treeView TreeView head (apply array child-views) button-options)
      (.leafView TreeView head button-options))))

(defn- display-inline [this editor range tree error?]
  (let [end (-> range .-end .-row)
        tree-view (build-tree-view tree)]
    (.removeLines ink/Result editor end end)
    (new ink/Result editor #js[end end] #js{:content tree-view
                                            :error error?
                                            :type (if error? "block" "inline")
                                            :scope "proto-repl"})))

(defn- maybe-wrap-do-block [code]
  (if (or (re-matches #"\s*[A-Za-z0-9\-!?.<>:\/*=+_]+\s*" code)
          (re-matches #"\s*\([^\(\)]+\)\s*" code))
    code
    (str "(do " code ")")))

(defn- when-not-running [this func]
  (if (running? this)
    (stderr this "REPL alrady running")
    (func)))

(defn- display-current-ns [{:keys [view current-ns]}]
  (rv/info view (str @current-ns "=>")))

(defn- get-connected-pid [{:keys [new-connection]}]
  (go-try-log
    (-> (nrepl/eval @new-connection {:code '(.pid (java.lang.ProcessHandle/current))})
        <! :value (or "unknown"))))

(defn- display-ns-message [{:keys [view current-ns] :as this} ns status]
    (if (:namespace-not-found status)
      (rv/stderr view (str "Namespace not found: " ns "\n\n"
                           "Try loading the current file.  "
                           (->> (get-keybindings :load-current-file)
                                (map #(str "(" % ")"))
                                (str/join "  or  "))))
      (reset! current-ns ns))
    (display-current-ns this))

(defn- display-nrepl-message [{:keys [view current-ns] :as this}
                              {:keys [out err ns value status ex]}]
  (cond
    out (rv/stdout view out)
    err (rv/stderr view err)
    value (rv/result view (pretty-edn value))
    ex (rv/stderr view "\nEvaluate *e to see the full stack trace."))
  (when ns (display-ns-message this ns status)))

(defn- eval-and-display* [{:keys [new-connection view current-ns spinner] :as this}
                          {:keys [code ns editor range]}]
  (go-try-log
    (when (get-config :display-executed-code-in-repl)
      (rv/display-executed-code view code))
    (let [spinid (when (and editor range) (.startAt spinner editor range))]
      (dochan! [m (nrepl/request @new-connection
                                 (merge {:op "eval" :code code :ns (or ns @current-ns)}
                                        (when editor {:file (.getPath editor)})
                                        (when range {:line (-> range .-start .-row inc)
                                                     :column (-> range .-start .-column inc)})))]
        (display-nrepl-message this m))
      (when spinid (.stop spinner editor spinid)))))

(defrecord ^:private ReplImpl [emitter current-ns spinner connection
                               new-connection view]
  Repl
  (clear [_] (rv/clear view))

  (eval-and-display [this options]
    (eval-and-display* this options))

  (execute-entered-text [this]
    (when (running? this)
      (when-let [code (not-empty (rv/get-and-clear-entered-text view))]
        (eval-and-display this {:code code}))))

  (exit [this]
    (when (running? this)
      (info this "Stopping REPL")
      (nrepl/close @new-connection)
      (reset! new-connection nil)))

  (inline-result-handler [this result {:keys [inlineOptions]}]
    (when (and inlineOptions (get-config :show-inline-results))
      ((make-inline-handler this (:editor inlineOptions) (:range inlineOptions)
                            #(js->clj (edn->display-tree %)))
       result)))

  (interrupt [_]
    (nrepl/interrupt @new-connection)
    (rv/stderr view "Interrupted.\n"))

  (make-inline-handler [this editor range value->tree]
    (fn [result]
      (let [{:keys [value error]} (obj->map result)]
        (display-inline this editor range
                        (if value (value->tree value) [error])
                        (not value)))))

  (on-did-close [_ callback]
    (.on emitter "proto-repl-repl:close" callback))

  (on-did-start [_ callback]
    (.on emitter "proto-repl-repl:start" callback))

  (on-did-stop [_ callback]
    (.on emitter "proto-repl-repl:stop" callback))

  (running? [_] (boolean @new-connection))

  (start-remote-repl-connection [this {:keys [host port]}]
    (if (running? this)
      (rv/stderr view "already connected")
      (go-try-log
        (let [c (<! (nrepl/create-client {:host host :port port}))]
          (if-let [err (ex-message c)]
            (rv/stderr view err)
            (do
              (reset! new-connection c)
              (rv/info view (str "Connected to " host ":" port "\n"))
              (dochan! [m (nrepl/request c {:op "describe"})]
                (when-let [{:keys [clojure java nrepl]} (:versions m)]
                  (rv/info view (str "• Clojure " (:version-string clojure) "\n"
                                     "• Java " (:version-string java) "\n"
                                     "• nREPL " (:version-string nrepl) "\n"
                                     "• pid " (<! (get-connected-pid this)) "\n"))))
              (display-current-ns this)
              (if-let [err (<! (:error-chan c))]
                (rv/stderr view (str err))
                (rv/stderr view "nREPL connection was closed"))))
          (reset! new-connection nil)))))

  (doc [_ text] (rv/doc view text))
  (info [_ text] (rv/info view text))
  (stderr [_ text] (rv/stderr view text))
  (stdout [_ text] (rv/stdout view text)))

(defn make-repl []
  (when (not ink/ink) (throw (js/Error. "The package 'ink' is required.")))
  (let [connection (atom nil)
        new-connection (atom nil)
        view (make-ink-repl-view)
        emitter (Emitter.)
        repl (map->ReplImpl {:current-ns (atom "user")
                             :emitter emitter
                             :spinner (Spinner.)
                             :connection connection
                             :new-connection new-connection
                             :view view})]
    (when (get-config :display-help-text)
      (info repl repl-help-text))
    (rv/on-did-close view
      ; FIXME exception in close handler causes REPL to not be able to start again.
      (fn [] (try (some-> @new-connection nrepl/close)
                  (.emit emitter "proto-repl-repl:close")
                  (catch :default e (js/console.error "Error while closing repl:" e)))))
    repl))
