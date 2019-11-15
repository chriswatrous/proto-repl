(ns proto-repl.repl
  (:require [proto-repl.utils :refer [pretty-edn obj->map]]))

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
                (-> this
                    :old-repl
                    .-replView
                    (.result (if (js/atom.config.get "proto-repl.autoPrettyPrint")
                               (pretty-edn value)
                               value)))))))

(defrecord ^:private ReplImpl [emitter loading-indicator old-repl]
  Repl
  (clear [_] (.clear old-repl))

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
                (.emit (.-emitter old-repl) "proto-repl-repl:close")
                (catch :default e (js/console.log "Warning error while closing:" e)))))))))

  (execute-code [_ code options]
    (.executeCode old-repl code (clj->js (or options {}))))

  (execute-entered-text [_] (.executeEnteredText old-repl))
  (exit [_] (.exit old-repl))
  (get-type [_] (-> old-repl .-process .getType))
  (inline-result-handler [_] (.inlineResultHandler old-repl))
  (interrupt [_] (.interrupt old-repl))

  (make-inline-handler [_ editor range value->tree]
    (.makeInlineHandler old-repl editor range value->tree))

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

  (doc [_ text] (.doc old-repl text))
  (info [_ text] (.info old-repl text))
  (stderr [_ text] (.stderr old-repl text))
  (stdout [_ text] (.stdout old-repl text)))


(defn make-repl [old-repl]
  (map->ReplImpl {:emitter (.-emitter old-repl)
                  :loading-indicator (.-loadingIndicator old-repl)
                  :old-repl old-repl}))
