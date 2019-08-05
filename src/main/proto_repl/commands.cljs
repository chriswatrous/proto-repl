(ns proto-repl.commands
  (:require [clojure.pprint :as pp]
            ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor get-var-under-cursor]]
            [proto-repl.plugin :refer [execute-code
                                       execute-code-in-ns
                                       info
                                       self-hosted?
                                       state-get
                                       state-merge!
                                       stderr]]))


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


(defn- execute-ranges [editor ranges]
  (when-let [range (first ranges)]
    (execute-code-in-ns
      (.getTextInBufferRange editor)
      {:inlineOptions {:editor editor
                       :range range}
       :displayInRepl false
       :resultHandler (fn [result options]
                        (.inlineResultHandler (state-get :repl))
                        (execute-ranges editor (rest ranges)))})))


(defn- get-top-level-ranges [editor]
  (into [] (.getTopLevelRanges editor-utils editor)))


(defn autoeval-file
  "Turn on auto evaluation of the current file."
  []
  (let [ink (state-get :ink)
        editor (get-active-text-editor)]
    (cond (not (js/atom.config.get "proto-repl.showInlineResults"))
          (stderr "Auto Evaling is not supported unless inline results is enabled")
          (not ink)
          (stderr "Install Atom Ink package to use auto evaling.")
          editor
          (if editor.protoReplAutoEvalDisposable
            (stderr "Already auto evaling")
            (do
              (set! editor.protoReplAutoEvalDisposable
                    (.onDidStopChanging
                      editor
                      (fn [] (.removeAll ink.Result editor)
                             (execute-ranges editor (get-top-level-ranges editor)))))
              (execute-ranges editor (get-top-level-ranges editor)))))))


(defn stop-autoeval-file
  "Turns off autoevaling of the current file."
  []
  (when-let [editor (get-active-text-editor)]
    (when editor.protoReplAutoEvalDisposable
      (.dispose editor.protoReplAutoEvalDisposable)
      (set! editor.protoReplAutoEvalDisposable nil))))


(defn clear-repl []
  (some-> (state-get :repl) .clear))


(defn interrupt []
  "Interrupt the currently executing command."
  (.interrupt (state-get :repl)))


(defn exit-repl []
  (.exit (state-get :repl)))


(defn pretty-print
  "Pretty print the last value"
  []
  (execute-code (str '(do (require 'clojure.pprint) (clojure.pprint/pp)))))


(defn execute-text-entered-in-repl []
  (some-> (state-get :repl) .executeEnteredText))


(def ^:private refresh-namespaces-code
  '(do
    (try
      (require 'user)
      (catch java.io.FileNotFoundException e
        (println (str "No user namespace defined. Defaulting to "
                      "clojure.tools.namespace.repl/refresh."))))
    (try
      (require 'clojure.tools.namespace.repl)
      (catch java.io.FileNotFoundException e
        (println (str "clojure.tools.namespace.repl not available. Add proto-repl in your "
                      "project.clj as a dependency to allow refresh. See "
                      "https://clojars.org/proto-repl"))))
    (let [refresh (or (find-var 'user/reset) (find-var 'clojure.tools.namespace.repl/refresh))
          result
          (if refresh
            (refresh)
            (println (str "You can use your own refresh function, just define reset function in "
                          "user namespace\n"
                          "See this https://github.com/clojure/tools.namespace#"
                          "reloading-code-motivation for why you should use it.")))]
      (when (isa? (type result) Exception)
        (println (.getMessage result)))
      result)))


(defn- refresh-result-handler [result callback]
  ; Value will contain an exception if it's not valid otherwise it will be nil
  ; nil will also be returned if there is no clojure.tools.namespace available.
  ; The callback will still be invoked in that case. That's important so that
  ; run all tests will still work without it.
  (cond result.value
        (do (info "Refresh complete")
            ; Make sure the extension process is running after ever refresh.
            ; If refreshing or loading code had failed the extensions feature might not
            ; have stopped itself.
            (.startExtensionRequestProcessing (state-get :extensionsFeature))
            (when callback (callback)))
        result.error
        (stderr (str "Refresh Warning: " result.error))))


(defn refresh-namespaces
  "Refreshes any changed code in the project since the last refresh. Presumes
  clojure.tools.namespace is a dependency and setup with standard user/reset
  function. Will invoke the optional callback if refresh is successful."
  ([] (refresh-namespaces nil))
  ([callback]
   (if (self-hosted?)
     (stderr "Refreshing not supported in self hosted REPL.")
     (do (info "Refreshing code...")
         (execute-code refresh-namespaces-code
                       {:displayInRepl false
                        :resultHandler #(refresh-result-handler % callback)})))))


;;;; Repl starting commands ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn toggle-auto-scroll []
  (let [key "proto-repl.autoScroll"]
    (.set js/atom.config key (not (.get js/atom.config key)))))
