(ns proto-repl.repl
  (:require ["atom" :refer [Emitter]]
            [proto-repl.utils :refer [pretty-edn obj->map edn->display-tree]]
            [proto-repl.ink :as ink]))

(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))
(def ^:private RemoteReplProcess (js/require "../lib/process/remote-repl-process"))
(def ^:private SelfHostedProcess (js/require "../lib/process/self-hosted-process"))
(def ^:private TreeView (js/require "../lib/tree-view"))
(def ^:private Spinner (js/require "../lib/load-widget"))

(defprotocol Repl
  (clear [this])
  (execute-code* [this code options])
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
  (self-hosted? [this])
  (start-process-if-not-running [this project-path])
  (start-remote-repl-connection [this {:keys [host port]}])
  (start-self-hosted-connection [this])

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

(defn- handle-connection-message [this msg]
  (let [{:keys [out err value]} (obj->map msg)]
    (cond
      out (stdout this out)
      err (stderr this err)
      value (do (info this (str (.getCurrentNs @(:process this)) "=>"))
                (-> this :view
                    (.result (if (js/atom.config.get "proto-repl.autoPrettyPrint")
                                 (pretty-edn value) value)))))))

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

(defrecord ^:private ReplImpl [emitter spinner extensions-feature process view session]
  Repl
  (clear [_] (.clear view))

  ; Executes the given code string.
  ; Valid options:
  ;   :resultHandler - a callback function to invoke with the value that was read. If this is
  ;                    passed in then the value will not be displayed in the REPL.
  ;   :displayCode - Code to display in the REPL. This can be used when the code
  ;                  executed is wrapped in eval or other code that shouldn't be displayed to
  ;                  the user.
  ;   :displayInRepl - Boolean to indicate if the result value or error should be
  ;                    displayed in the REPL. Defaults to true.
  ;   :doBlock - Boolean to indicate if the incoming code should be wrapped in a
  ;              do block when it contains multiple statements.
  (execute-code* [this code {:keys [resultHandler displayCode inlineOptions doBlock]
                             :as options}]
    (when (running? this)
      (when (and displayCode (js/atom.config.get "proto-repl.displayExecutedCodeInRepl"))
        (.displayExecutedCode view displayCode))
      (let [spinid (when inlineOptions (.startAt spinner
                                                 (:editor inlineOptions)
                                                 (:range inlineOptions)))
            command (if doBlock (maybe-wrap-do-block code) code)]
        (.sendCommand @process command (clj->js options)
          (fn [result]
            (.stop spinner (:editor inlineOptions) spinid)
            (when-not (some->> result .-value (.handleReplResult extensions-feature))
              (if resultHandler
                (resultHandler result)
                (inline-result-handler this result options))))))))

  (execute-entered-text [this]
    (when (running? this)
      (.executeEnteredText view)))

  (exit [this]
    (when (running? this)
      (info this "Stopping REPL")
      (.stop @process)
      (reset! process nil)))

  (get-type [_] (.getType @process))

  (inline-result-handler [this result {:keys [inlineOptions]}]
    (when (and inlineOptions (js/atom.config.get "proto-repl.showInlineResults"))
      ((make-inline-handler this (:editor inlineOptions) (:range inlineOptions)
                            #(js->clj (edn->display-tree %)))
       result)))

  (interrupt [_]
    (.clearAll spinner)
    (.interrupt @process))

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

  (running? [_] (some-> @process .running))
  (self-hosted? [this] (-> this get-type (= "SelfHosted")))

  (start-process-if-not-running [this project-path]
    (if (running? this)
      (stderr this "REPL already running")
      (do (reset! process (LocalReplProcess. view))
          (.start @process project-path
                  #js {:messageHandler #(handle-connection-message this %)
                       :startCallback #(handle-repl-started this)
                       :stopCallback #(handle-repl-stopped this)}))))

  (start-remote-repl-connection [this {:keys [host port]}]
    (if (running? this)
      (stderr this "REPL alrady running")
      (do (reset! process (RemoteReplProcess. view))
          (info this (str "Starting remote REPL connection on " host ":" port))
          (.start @process
                  #js {:host host
                       :port port
                       :messageHandler #(handle-connection-message this %)
                       :startCallback #(handle-repl-started this)
                       :stopCallback #(handle-repl-stopped this)}))))

  (start-self-hosted-connection [this]
    (if (running? this)
      (stderr this "REPL alrady running")
      (do (reset! process (SelfHostedProcess. view))
          (.start @process
                  #js {:messageHandler #(handle-connection-message this %)
                       :startCallback (fn [] (info this "Self Hosted REPL Started!")
                                             (handle-repl-started this))
                       :stopCallback #(handle-repl-stopped this)}))))

  (doc [_ text] (.doc view text))
  (info [_ text] (.info view text))
  (stderr [_ text] (.stderr view text))
  (stdout [_ text] (.stdout view text)))


(defn make-repl [extensions-feature]
  (when (not ink/ink) (throw (js/Error. "The package 'ink' is required.")))
  (let [process (atom nil)
        session (atom nil)
        view (InkConsole. ink/ink)
        emitter (Emitter.)
        repl (map->ReplImpl {:emitter emitter
                             :spinner (Spinner.) :extensions-feature extensions-feature
                             :process process
                             :view view
                             :session session})]
    (.onDidOpen view
      (fn []
        (when (js/atom.config.get "proto-repl.displayHelpText")
          (info repl repl-help-text))))
    (.onDidClose view
      (fn []
        (try
          (some-> @process (.stop @session))
          (.emit emitter "proto-repl-repl:close")
          (catch :default e (js/console.log "Warning error while closing:" e)))))
    repl))
