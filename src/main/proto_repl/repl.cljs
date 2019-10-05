(ns proto-repl.repl
  (:require ["atom" :refer [Emitter]]))


(def ^:private ReplTextEditor (js/require "../lib/views/repl-text-editor"))
(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private Spinner (js/require "../lib/load-widget"))


(defprotocol Repl
  (clear [this])
  (execute-code [this options])
  (exit [this])
  (get-repl-type [this])
  (interrupt [this])
  (running? [this])
  (self-hosted? [this])

  (doc [this text])
  (info [this text])
  (stderr [this text])
  (stdout [this text]))


(def ^:private repl-help-text "REPL Instructions

Code can be entered at the bottom and executed by pressing shift+enter.
Try it now by typing (+ 1 1) in the bottom section and pressing shift+enter.

Working in another Clojure file and sending forms to the REPL is the most efficient way to work. Use the following key bindings to send code to the REPL. See the settings for more keybindings.

ctrl-alt-, then b
Execute block. Finds the block of Clojure code your cursor is in and executes that.

ctrl-alt-, s
Executes the selection. Sends the selected text to the REPL.

You can disable this help text in the settings.")


(defrecord ^:private ReplImpl [emitter
                               extensions-feature
                               ink
                               loading-indicator
                               process
                               repl-view]
  Repl
  (clear [this] (-> this :repl-view .clear))
  (running? [this] (-> this :process deref .running))
  ; (running? [this] (.running @(:process this)))

  (interrupt [this]
    (-> this :loading-indicator .clearAll)
    (-> this :process deref .interrupt))

  (exit [this]
    (when (running? this)
      (info this "Stopping REPL")
      (-> this :process deref .stop)))
      ; (-> this :process (reset! nil))))

  (doc [this text] (-> this :repl-view (.doc text)))
  (info [this text] (-> this :repl-view (.info text)))
  (stderr [this text] (-> this :repl-view (.stderr text)))
  (stdout [this text] (-> this :repl-view (.stdout text))))


(comment
  (def r
    (let [repl (proto-repl.plugin/state-get :repl)]
      (map->ReplImpl
        {:repl-view (.-replView repl)
         :process (atom (.-process repl))
         :loading-indicator (.-loadingIndicator repl)})))

  (running? r)
  (clear r)
  (interrupt r)
  (exit r)

  (stderr r "Qwer\n")
  (stdout r "Qwer\n")
  (doc r "Qwer\n")
  (info r "Qwer\n"))


(defn make-repl [{:keys [ink on-did-close on-did-start on-did-stop]
                  :as options}]
  (let [emitter (Emitter.)
        repl
        (map->ReplImpl
          (merge options
                 {:emitter emitter
                  :loading-indicator (Spinner.)
                  :repl-view (if (and ink (js/atom.config.get "proto-repl.inkConsole"))
                               (InkConsole. ink)
                               (ReplTextEditor.))}))]
    (doto (:repl-view repl)
      (.onDidOpen
        (fn []
          (when (js/atom.config.get "proto-repl.displayHelpText")
            (info repl repl-help-text))
          (when (and (not ink) (js/atom.config.get "proto-repl.inkConsole"))
            (info repl (str "Atom Ink does not appear to be installed. Install it to get a "
                            "better REPL experience.")))))
      (.onDidClose
        (fn []
          (try
            (some-> (:process repl) (.stop (:session repl)))
            (.emit (:emitter repl) "proto-repl-repl:close")))))
    repl))
