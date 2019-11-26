(ns proto-repl.views.nrepl-connection-view
  (:require ["atom-space-pen-views" :refer [$ TextEditorView View]
                                    :rename {$ jquery}]
            ["fs" :as fs]
            [proto-repl.macros :refer-macros [this-fn]]))

(def ^:private default-host-text "localhost")
(def ^:private default-port-text (atom ""))

(defn- get-nrepl-port []
  (let [path (->> (.getPaths js/atom.project)
                  (map #(str % "/.nrepl-port"))
                  (filter fs/existsSync)
                  first)]
    (if path (str (fs/readFileSync path)) "")))

(def ^:private static-methods
  {:content
   (this-fn [this]
     (.div this #js {:class "proto-repl proto-repl-nrepl-connection-dialog"}
       (fn []
         (.h3 this #js {:class "icon icon-plug"} "nRepl connection")
         (.div this #js {:class "block"}
           (fn []
             (.label this "Host")
             (.subview this "hostEditor"
                       (TextEditorView. #js {:mini true
                                             :placeholderText default-host-text
                                             :attributes #js {:tabindex 1}}))))
         (.div this #js {:class "block"}
           (fn []
             (.label this "Port")
             (.subview this "portEditor"
                       (TextEditorView. #js {:mini true
                                             :placeholderText @default-port-text
                                             :attributes #js {:tabindex 2}})))))))})

(def ^:private instance-methods
  {:initialize
   (this-fn [this]
     (js/console.log (js/Error. "nrepl-connection-view initialize"))
     (.add js/atom.commands (.-element this)
           #js {"core:confirm" #(.onConfirm this)
                "core:cancel" #(.onCancel this)}))

   :show
   (this-fn [this]
     (when-not (.-panel this)
       (set! (.-panel this) (.addModalPanel js/atom.workspace
                                            #js {:item this :visible false})))
     (.storeActiveElement this)
     (.resetEditors this)
     (-> this .-panel .show)
     (-> this .-hostEditor .focus)
     (let [port (get-nrepl-port)]
       (reset! default-port-text port)
       (-> this .-portEditor .getModel (.setPlaceholderText port))))

   :storeActiveElement
   (this-fn [this]
     (set! (.-previousActiveElement this) (jquery (.-activeElement js/document))))

   :resetEditors
   (this-fn [this]
     (-> this .-hostEditor (.setText ""))
     (-> this .-portEditor (.setText "")))

   :restoreFocus
   (this-fn [this] (some-> this .-previousActiveElement .focus))

   :toggleFocus
   (this-fn [this]
     (.focus (if (-> this .-hostEditor .-element .hasFocus)
               (.-portEditor this) (.-hostEditor this))))

   :onCancel
   (this-fn [this]
     (some-> this .-panel .hide)
     (.restoreFocus this))

   :onConfirm
   (this-fn [this]
     (let [host (-> this .-hostEditor .getText)
           host (if (empty? host) default-host-text host)
           port (-> this .-portEditor .getText)
           port (if (empty? port) @default-port-text port)
           cb (.-confirmCallback this)]
       (when (fn? cb) (cb {:host host
                           :port (js/parseInt port)})))
     (-> this .-panel .hide))})

(def NReplConnectionView
  (let [constructor
        (this-fn [this confirm-callback]
          (set! (.-confirmCallback this) confirm-callback)
          (.call View this confirm-callback))]
    (js/Object.assign constructor View (clj->js static-methods))
    (set! (.-prototype constructor)
          (js/Object.assign (js/Object.create (.-prototype View))
                            #js {:constructor constructor}
                            (clj->js instance-methods)))
    constructor))
