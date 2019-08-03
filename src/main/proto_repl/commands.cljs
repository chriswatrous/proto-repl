(ns proto-repl.commands
  (:require ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor get-var-under-cursor]]
            [proto-repl.plugin :refer [execute-code-in-ns state-merge! state-get stderr]]))


(def ^:private lodash (js/require "lodash"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))
(def ^:private Repl (js/require "../lib/repl"))
(def ^:private NReplConnectionView (js/require "../lib/views/nrepl-connection-view"))


(defn- flash-highlight-range [editor range]
  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor marker #js {:type "highlight" :class "block-execution"})
    (js/setTimeout #(.destroy marker) 350)))


(defn execute-block [options]
  (when-let [editor (.getActiveTextEditor js/atom.workspace)]
    (when-let [range (.getCursorInBlockRange editor-utils editor (clj->js options))]
      (flash-highlight-range editor range)
      (let [text (-> editor (.getTextInBufferRange range) .trim)
            options (assoc options :displayCode text
                                   :inlineOptions {:editor editor
                                                   :range range})]
        (execute-code-in-ns text options)))))


(defn execute-selected-text
  "Executes the selected code."
  ([] (execute-selected-text {}))
  ([options]
   (when-let [editor (get-active-text-editor)]
     (let [text (.getSelectedText editor)
           text (if (empty? text) (get-var-under-cursor editor) text)
           range (.getSelectedBufferRange editor)]
       (when (lodash.isEqual range.start range.end)
         (set! range.end.column ##Inf))
       (execute-code-in-ns text (merge options
                                       {:inlineOptions {:editor editor
                                                        :range range}
                                        :displaycode text
                                        :doBlock true}))))))


(defn clear-repl []
  (some-> (state-get :repl) .clear))


(defn interrupt []
  "Interrupt the currently executing command."
  (.interrupt (state-get :repl)))


(defn exit-repl []
  (.exit (state-get :repl)))


;;;; Repl starting commands ;;;;

(defn- prepare-repl [repl]
  (let [pane (.getActivePane js/atom.workspace)]
    (doto repl
      (.consumeInk (state-get :ink))
      (.onDidClose (fn [] (state-merge! {:repl nil})
                          (.emit (state-get :emitter) "proto-repl:closed")))
      (.onDidStart (fn [] (.emit (state-get :emitter) "proto-repl:connected")
                          (when (.get js/atom.config "proto-repl.refreshOnReplStart")
                            ((state-get :refreshNamespaces)))
                          (.activate pane)))
      (.onDidStop (fn [] (.stopExtensionRequestProcessing (state-get :extensionsFeature))
                         (.emit (state-get :emitter) "proto-repl:stopped"))))))


(defn toggle
  "Start the REPL if it's not currently running."
  ([] (toggle nil))
  ([project-path]
   (when-not (state-get :repl)
     (state-merge! {:repl (doto (Repl. (state-get :extensionsFeature))
                            prepare-repl
                            (.startProcessIfNotRunning project-path))}))))


(defn toggle-current-editor-dir
  "Start the REPL in the directory of the file in the current editor."
  []
  (some-> (get-active-text-editor)
          .getPath
          dirname
          toggle))


(defn spy [x] (do (println "spy:" x) x))

(defn remote-nrepl-connection
  "Open the nRepl connection dialog."
  []
  ; (when-not (state-get :connectionView)
  (state-merge!
    {:connectionView
     (doto (NReplConnectionView.
             (fn [params]
               (when-not (state-get :repl)
                 (state-merge! {:repl (spy (doto (Repl. (state-get :extensionsFeature))
                                             prepare-repl
                                             (.startRemoteReplConnection params)))
                                :connectionView nil}))))
       .show)}))


(defn start-self-hosted-repl []
  (when-not (state-get :repl)
    (state-merge! {:repl (doto (Repl. (state-get :extensionsFeature))
                            prepare-repl)}))
  (.startSelfHostedConnection (state-get :repl)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn toggle-auto-scroll []
  (let [key "proto-repl.autoScroll"]
    (.set js/atom.config key (not (.get js/atom.config key)))))
