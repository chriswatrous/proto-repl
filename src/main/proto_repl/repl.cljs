(ns proto-repl.repl
  (:require ["atom" :refer [Emitter]]))


(def ^:private ReplTextEditor (js/require "../lib/views/repl-text-editor"))
(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private Spinner (js/require "../lib/load-widget"))
(def ^:private TreeView (js/require "../lib/tree-view"))


(defprotocol Repl
  (clear [this])
  (execute-code [this options])
  (execute-entered-text [this])
  (exit [this])
  (get-repl-type [this])
  (interrupt [this])
  (running? [this])
  (self-hosted? [this])
  (display-inline [this editor range tree error?])

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


(defn needs-do-block? [code]
  ; currently only white lists for single symbol/keyword, such as :cljs/quit
  ; or single un-nested call, such as (fig-status)
  (not (or (re-matches #"^\s*[A-Za-z0-9\-!?.<>:\/*=+_]+\s*$" code)
           (re-matches #"^\s*\([^\(\)]+\)\s*$" code))))


(defn build-tree-view [[head button-options children]]
  (if (seq children)
    (TreeView.treeView
      head
      (->> children
           (map #(if (sequential? %) (build-tree-view %) (TreeView.leafView % #js{})))
           clj->js)
      button-options)
    (TreeView.leafView head (or button-options #js{}))))


(defrecord ^:private ReplImpl [emitter
                               extensions-feature
                               ink
                               loading-indicator
                               process
                               repl-view]
  Repl
  (clear [this] (-> this :repl-view .clear))
  (running? [this] (.running @(:process this)))

  (interrupt [this]
    (-> this :loading-indicator .clearAll)
    (-> this :process deref .interrupt))

  (exit [this]
    (when (running? this)
      (info this "Stopping REPL")
      (-> this :process deref .stop)))
      ; (-> this :process (reset! nil))))

  (execute-entered-text [this]
    (when (running? this)
      (-> this :repl-view .executeEnteredText)))

  ; (execute-code [this code options]
  ;   (when (running? this)))

  (display-inline [this editor range tree error?]
    (let [tree (js->clj tree :keywordize-keys true)
          end range.end.row
          view (build-tree-view tree)
          Result (-> this :ink .-Result)]
      (.removeLines Result editor end end)
      (Result. editor #js[end end] #js{:content (clj->js view)
                                       :error error?
                                       :type (if error? "block" "inline")
                                       :scope "proto-repl"})))

  (doc [this text] (-> this :repl-view (.doc text)))
  (info [this text] (-> this :repl-view (.info text)))
  (stderr [this text] (-> this :repl-view (.stderr text)))
  (stdout [this text] (-> this :repl-view (.stdout text))))


(comment
  (def r
    (let [repl (:repl @proto-repl.master/state)]
      (map->ReplImpl
        {:repl-view (.-replView repl)
         :process (atom (.-process repl))
         :loading-indicator (.-loadingIndicator repl)
         :ink (.-ink repl)})))

  (do js/global.displayInlineArgs)
  (apply display-inline r js/global.displayInlineArgs)

  (-> @state :repl
      (.displayInline (aget js/global.displayInlineArgs 0)
                      (aget js/global.displayInlineArgs 1)
                      (aget js/global.displayInlineArgs 2)
                      (aget js/global.displayInlineArgs 3)))

  (execute-entered-text r)
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
