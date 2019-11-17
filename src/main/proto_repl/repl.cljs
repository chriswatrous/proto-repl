(ns proto-repl.repl
  (:require ["atom" :refer [Emitter]]
            [proto-repl.utils :refer [pretty-edn obj->map edn->display-tree]]))

(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))
(def ^:private RemoteReplProcess (js/require "../lib/process/remote-repl-process"))
(def ^:private SelfHostedProcess (js/require "../lib/process/self-hosted-process"))
(def ^:private TreeView (js/require "../lib/tree-view"))
(def ^:private Spinner (js/require "../lib/load-widget"))

(defprotocol Repl
  (clear [this])
  (consume-ink [this ink])
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
      value (do (info this (str (.getCurrentNs (-> this :old-repl .-process)) "=>"))
                (-> this :old-repl .-replView
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
        Result (-> this :old-repl .-ink .-Result)
        view (build-tree-view tree)]
    (.removeLines Result editor end end)
    (new Result editor #js[end end] #js{:content view
                                        :error error?
                                        :type (if error? "block" "inline")
                                        :scope "proto-repl"})))

(defn- maybe-wrap-do-block [code]
  (if (or (re-matches #"\s*[A-Za-z0-9\-!?.<>:\/*=+_]+\s*" code)
          (re-matches #"\s*\([^\(\)]+\)\s*" code))
    code
    (str "(do " code ")")))

(defrecord ^:private ReplImpl [emitter loading-indicator extensions-feature old-repl]
  Repl
  (clear [_] (-> old-repl .-replView .clear))

  (consume-ink [this ink]
    (when (not ink) (throw (js/Error. "The package 'ink' is required.")))
    (do
      (set! (.-ink old-repl) ink)
      (set!
        (.-replView old-repl)
        (doto (InkConsole. ink)
          (.onDidOpen
            (fn []
              (when (js/atom.config.get "proto-repl.displayHelpText")
                (info this repl-help-text))
              (when (and (not ink) (js/atom.config.get "proto-repl.inkConsole"))
                (info this (str "Atom Ink does not appear to be installed. Install it "
                                "to get a better REPL experience.")))))
          (.onDidClose
            (fn []
              (try
                (some-> old-repl .-process (.stop (.-session old-repl)))
                (set! (.-replView old-repl) nil)
                (.emit emitter "proto-repl-repl:close")
                (catch :default e (js/console.log "Warning error while closing:" e)))))))))

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
      (let [js-options (clj->js options)]
        (when (and displayCode (js/atom.config.get "proto-repl.displayExecutedCodeInRepl"))
          (-> old-repl .-replView (.displayExecutedCode displayCode)))
        (let [spinid (when inlineOptions (.startAt loading-indicator
                                                   (:editor inlineOptions)
                                                   (:range inlineOptions)))
              command (if doBlock (maybe-wrap-do-block code) code)]
          (-> old-repl .-process
              (.sendCommand command js-options
                (fn [result]
                  (.stop loading-indicator (some-> inlineOptions :editor) spinid)
                  (when-not (some->> result .-value (.handleReplResult extensions-feature))
                    (if resultHandler
                      (resultHandler result)
                      (inline-result-handler this result options))))))))))

  (execute-entered-text [this]
    (when (running? this)
      (-> old-repl .-replView .executeEnteredText)))

  (exit [this]
    (when (running? this)
      (info this "Stopping REPL")
      (-> old-repl .-process .stop)
      (set! (.-process old-repl) nil)))

  (get-type [_] (-> old-repl .-process .getType))

  (inline-result-handler [this result {:keys [inlineOptions]}]
    (let [old-repl (:old-repl this)]
      (when (and (.-ink old-repl) inlineOptions
                 (js/atom.config.get "proto-repl.showInlineResults"))
        ((make-inline-handler this (:editor inlineOptions) (:range inlineOptions)
                              #(js->clj (edn->display-tree %)))
         result))))

  (interrupt [_]
    (.clearAll loading-indicator)
    (-> old-repl .-process .interrupt))

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

  (running? [_] (some-> old-repl .-process .running))
  (self-hosted? [this] (-> this get-type (= "SelfHosted")))

  (start-process-if-not-running [this project-path]
    (if (running? this)
      (stderr this "REPL already running")
      (let [process (set! (.-process old-repl) (LocalReplProcess. (.-replView old-repl)))]
        (set! (.-process old-repl) process)
        (.start process project-path
                #js {:messageHandler #(handle-connection-message this %)
                     :startCallback #(handle-repl-started this)
                     :stopCallback #(handle-repl-stopped this)}))))

  (start-remote-repl-connection [this {:keys [host port]}]
    (if (running? this)
      (stderr this "REPL alrady running")
      (let [process (RemoteReplProcess. (.-replView old-repl))]
        (set! (.-process old-repl) process)
        (info this (str "Starting remote REPL connection on " host ":" port))
        (.start process
                #js {:host host
                     :port port
                     :messageHandler #(handle-connection-message this %)
                     :startCallback #(handle-repl-started this)
                     :stopCallback #(handle-repl-stopped this)}))))

  (start-self-hosted-connection [this]
    (if (running? this)
      (stderr this "REPL alrady running")
      (let [process (SelfHostedProcess. (.-replView old-repl))]
        (set! (.-process old-repl) process)
        (.start process
                #js {:messageHandler #(handle-connection-message this %)
                     :startCallback (fn [] (info this "Self Hosted REPL Started!")
                                           (handle-repl-started this))
                     :stopCallback #(handle-repl-stopped this)}))))

  (doc [_ text] (-> old-repl .-replView (.doc text)))
  (info [_ text] (-> old-repl .-replView (.info text)))
  (stderr [_ text] (-> old-repl .-replView (.stderr text)))
  (stdout [_ text] (-> old-repl .-replView (.stdout text))))


(defn make-repl [extensions-feature]
  (map->ReplImpl {:emitter (Emitter.)
                  :loading-indicator (Spinner.)
                  :extensions-feature extensions-feature
                  :old-repl #js{}}))
