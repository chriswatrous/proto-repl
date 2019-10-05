(ns proto-repl.repl
  (:require ["atom" :refer [Emitter]]))


(def ^:private ReplTextEditor (js/require "../lib/views/repl-text-editor"))
(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private Spinner (js/require "../lib/load-widget"))


(defprotocol Repl
  (doc [this text])
  (info [this text])
  (stderr [this text])
  (stdout [this text])
  (execute-code [this options])
  (exit [this])
  (get-repl-type [this])
  (interrupt [this])
  (running? [this])
  (self-hosted? [this]))


(def ^:private repl-help-text "REPL Instructions

Code can be entered at the bottom and executed by pressing shift+enter.
Try it now by typing (+ 1 1) in the bottom section and pressing shift+enter.

Working in another Clojure file and sending forms to the REPL is the most efficient way to work. Use the following key bindings to send code to the REPL. See the settings for more keybindings.

ctrl-alt-, then b
Execute block. Finds the block of Clojure code your cursor is in and executes that.

ctrl-alt-, s
Executes the selection. Sends the selected text to the REPL.

You can disable this help text in the settings.")


(defrecord ^:private ReplImpl [emitter extensions-feature ink repl-view]
  Repl
  (info [this text] (.info (:repl-view this) text))
  (doc [this text] (.doc (:repl-view this) text))
  (stdout [this text] (.stdout (:repl-view this) text))
  (stderr [this text] (.stderr (:repl-view this) text)))

  ; (execute-code [this code options]
  ;   (when [(:running (deref (:state this)))])))



(comment
  (def r (map->ReplImpl
           {:repl-view (-> :repl proto-repl.plugin/state-get .-replView)}))

  (stderr r "Qwer\n"))


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
