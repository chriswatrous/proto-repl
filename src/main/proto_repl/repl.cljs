(ns proto-repl.repl)

(def ^:private InkConsole (js/require "../lib/views/ink-console"))

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


(defrecord ^:private ReplImpl [old-repl]
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
  (get-type [this] (-> this :old-repl .getType))
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

  (running? [this] (-> this :old-repl .running))
  (self-hosted? [this] (-> this :old-repl .isSelfHosted))

  (doc [this text] (-> this :old-repl (.doc text)))
  (info [this text] (-> this :old-repl (.info text)))
  (stderr [this text] (-> this :old-repl (.stderr text)))
  (stdout [this text] (-> this :old-repl (.stdout text))))


(defn make-repl [old-repl]
  (->ReplImpl old-repl))
