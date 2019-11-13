(ns proto-repl.repl)

(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))
(def ^:private RemoteReplProcess (js/require "../lib/process/remote-repl-process"))
(def ^:private SelfHostedProcess (js/require "../lib/process/self-hosted-process"))

(defprotocol Repl
  (clear [this])
  (consume-ink [this ink])
  (display-inline [this editor range tree error?])
  (execute-code [this code options])
  (execute-entered-text [this])
  (exit [this])
  (get-type [this])
  (inline-result-handler [this])
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


(defrecord ^:private ReplImpl [emitter loading-indicator old-repl]
  Repl
  (clear [this] (-> this :old-repl .clear))

  (consume-ink [this ink]
    (when (not ink) (throw (js/Error. "The package 'ink' is required.")))
    (let [old-repl (-> this :old-repl)]
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
                (.emit (.-emitter old-repl) "proto-repl-repl:close")
                (catch :default e (js/console.log "Warning error while closing:" e)))))))))

  (execute-code [this code options]
    (-> this :old-repl (.executeCode code (clj->js (or options {})))))

  (execute-entered-text [this] (-> this :old-repl .executeEnteredText))
  (exit [this] (-> this :old-repl .exit))
  (get-type [this] (-> this :old-repl .-process .getType))
  (inline-result-handler [this] (-> this :old-repl .inlineResultHandler))
  (interrupt [this] (-> this :old-repl .interrupt))

  (make-inline-handler [this editor range value->tree]
    (-> this :old-repl (.makeInlineHandler editor range value->tree)))

  (on-did-close [this callback]
    (-> this :old-repl .-emitter (.on "proto-repl-repl:close" callback)))

  (on-did-start [this callback]
    (-> this :old-repl .-emitter (.on "proto-repl-repl:start" callback)))

  (on-did-stop [this callback]
    (-> this :old-repl .-emitter (.on "proto-repl-repl:stop" callback)))

  (running? [this] (some-> this :old-repl .-process .running))
  (self-hosted? [this] (-> this get-type (= "SelfHosted")))

  (start-process-if-not-running [this project-path]
    (if (running? this)
      (stderr this "REPL already running")
      (let [old-repl (:old-repl this)
            process (set! (.-process old-repl) (LocalReplProcess. (.-replView old-repl)))]
        (set! (.-process old-repl) process)
        (.start process project-path
                #js {:messageHandler #(.handleConnectionMessage old-repl %)
                     :startCallback #(.handleReplStarted old-repl)
                     :stopCallback #(.handleReplStopped old-repl)}))))

  (start-remote-repl-connection [this {:keys [host port]}]
    (if (running? this)
      (stderr this "REPL alrady running")
      (let [old-repl (:old-repl this)
            process (RemoteReplProcess. (.-replView old-repl))]
        (set! (.-process old-repl) process)
        (info this (str "Starting remote REPL connection on " host ":" port))
        (.start process
                #js {:host host
                     :port port
                     :messageHandler #(.handleConnectionMessage old-repl %)
                     :startCallback #(.handleReplStarted old-repl)
                     :stopCallback #(.handleReplStopped old-repl)}))))

  (start-self-hosted-connection [this]
    (if (running? this)
      (stderr this "REPL alrady running")
      (let [old-repl (:old-repl this)
            process (SelfHostedProcess. (.-replView old-repl))]
        (set! (.-process old-repl) process)
        (.start process
                #js {:messageHandler #(.handleConnectionMessage old-repl %)
                     :startCallback (fn [] (info this "Self Hosted REPL Started!")
                                           (.handleReplStarted old-repl))
                     :stopCallback #(.handleReplStopped old-repl)}))))

  (doc [this text] (-> this :old-repl (.doc text)))
  (info [this text] (-> this :old-repl (.info text)))
  (stderr [this text] (-> this :old-repl (.stderr text)))
  (stdout [this text] (-> this :old-repl (.stdout text))))

(def x #js{})
(comment
  (set! (.-a x) "qwer"))


(defn make-repl [old-repl]
  (map->ReplImpl {:emitter (.-emitter old-repl)
                  :loading-indicator (.-loadingIndicator old-repl)
                  :old-repl old-repl}))
