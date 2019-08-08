(ns proto-repl.commands
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor get-var-under-cursor]]
            [proto-repl.plugin :refer [doc
                                       execute-code
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


(defn execute-block
  ([] (execute-block {}))
  ([options]
   (when-let [editor (.getActiveTextEditor js/atom.workspace)]
     (when-let [range (.getCursorInBlockRange editor-utils editor (clj->js options))]
       (flash-highlight-range editor range)
       (let [text (-> editor (.getTextInBufferRange range) .trim)
             options (assoc options :displayCode text
                                    :inlineOptions {:editor editor
                                                    :range range})]
         (execute-code-in-ns text options))))))


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
  "Refresh any changed code in the project since the last refresh. Presumes
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


(defn super-refresh-namespaces
  "Refresh all of the code in the project whether it has changed or not.
  Presumes clojure.tools.namespace is a dependency and setup with standard
  user/reset function. Will invoke the optional callback if refresh is
  successful."
  ([] (super-refresh-namespaces nil))
  ([callback]
   (if (self-hosted?)
     (stderr "Refreshing not supported in self hosted REPL.")
     (do (info "Refreshing code...")
         (execute-code '(when (find-ns 'clojure.tools.namespace.repl)
                          (eval '(clojure.tools.namespace.repl/clear)))
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


(defn load-current-file []
  (when-let [editor (get-active-text-editor)]
    (if (self-hosted?)
      (stderr "Loading files is not supported yet in self hosted REPL.")
      (let [filename (.getPath editor)]
        (execute-code `(do (~'println "Loading File " ~filename)
                           (~'load-file ~filename)))))))


(defn run-tests-in-namespace []
  (when-let [editor (get-active-text-editor)]
    (if (self-hosted?)
      (stderr "Running tests is not supported yet in self hosted REPL.")
      (let [run #(execute-code-in-ns '(clojure.test/run-tests))]
        (if (js/atom.config.get "proto-repl.refreshBeforeRunningTestFile")
          (refresh-namespaces run)
          (run))))))


(defn run-test-under-cursor []
  (when-let [editor (get-active-text-editor)]
    (if (self-hosted?)
      (stderr "Running tests is not supported yet in self hosted REPL.")
      (when-let [test-name (get-var-under-cursor editor)]
        (let [run #(execute-code
                     `(do (clojure.test/test-vars [(var ~test-name)])
                          (~'println "tested" ~test-name)))]
          (if (js/atom.config.get "proto-repl.refreshBeforeRunningSingleTest")
            (refresh-namespaces run)
            (run)))))))


(defn run-all-tests [] ((state-get :runAllTests))
  (if (self-hosted?)
    (stderr "Running tests is not supported yet in self hosted REPL.")
    (refresh-namespaces
      #(execute-code '(def all-tests-future (future (time (clojure.test/run-all-tests))))))))


(defn- remove-dashes [s] (str/replace s #"^-+\n" ""))


(defn- get-doc-code [var-name]
  (-> (if (self-hosted?)
        '(clojure.core/with-out-str (doc var-name))
        '(do
           (require 'clojure.repl)
           (with-out-str (clojure.repl/doc var-name))))
      str
      (str/replace #"var-name" var-name)))


(comment
  (get-doc-code "abcd"))


(defn- parse-doc-result [value]
  (remove-dashes (if (self-hosted?) value (edn/read-string value))))


(defn- show-doc-result-inline [result var-name editor]
  (when (and (state-get :ink) (js/atom.config.get "proto-repl.showInlineResults"))
    (let [range (doto (.getSelectedBufferRange editor)
                  (lodash.set "end.column" ##Inf))]
      ((.makeInlineHandler (state-get :repl) range
                           (fn [v] #js [var-name nil [(parse-doc-result v)]]))
       result))))


(defn- show-doc-result-in-console [result var-name]
  (cond result.value (let [msg (str/trim (parse-doc-result result.value))]
                       (if (empty? msg)
                         (stderr (str "No doc found for " var-name))
                         (doc msg)))
        result.error (stderr result.error)
        :else (stderr (str "Did not get value or error from eval result:\n"
                           result))))


(defn- show-doc-result [result var-name editor]
  (show-doc-result-inline result var-name editor)
  (show-doc-result-in-console result var-name))


; This is to prevent the result handler from running more than once on error.
(defn- once [f]
  (let [count (atom 0)]
    (fn [& args]
      (when (= (swap! count inc) 1)
        (apply f args)))))


(defn print-var-documentation []
  (when-let [editor (get-active-text-editor)]
    (when-let [var-name (get-var-under-cursor editor)]
      (execute-code-in-ns (get-doc-code var-name)
                          {:displayInRepl false
                           :resultHandler (once #(show-doc-result % var-name editor))}))))


(defn print-var-code [] nil
  (when-let [editor (get-active-text-editor)]
    (when-let [var-name (get-var-under-cursor editor)]
      (if (self-hosted?)
        (stderr "Showing source code is not yet supported in self hosted REPL.")
        (execute-code-in-ns `(do
                               (~'require 'clojure.repl)
                               (~'with-out-str (clojure.repl/source ~(symbol var-name))))
                            {:displayInRepl false
                             :resultHandler (once #(show-doc-result % var-name editor))})))))


(defn- list-ns-vars-code [ns-name]
  (-> '(do
         (require 'clojure.repl)
         (let [selected-symbol 'ns-name
               selected-ns (get (ns-aliases *ns*) selected-symbol selected-symbol)]
           (str "Vars in " selected-ns ":\n"
                "------------------------------\n"
                (str/join "\n" (clojure.repl/dir-fn selected-ns)))))
      str
      (str/replace #"ns-name" ns-name)))


(defn list-ns-vars
  "Lists all the vars in the selected namespace or namespace alias"
  []
  (when-let [editor (get-active-text-editor)]
    (when-let [ns-name (get-var-under-cursor editor)]
      (println {:ns-name ns-name})
      (if (self-hosted?)
        (stderr "Listing namespace functions is not yet supported in self hosted REPL.")
        (execute-code-in-ns (list-ns-vars-code ns-name)
                            {:displayInRepl false
                             :resultHandler (once #(show-doc-result % ns-name editor))})))))


(defn list-ns-vars-with-docs-code [ns-name]
  (-> '(do
         (require 'clojure.repl)
         (require 'clojure.string)
         (let [selected-symbol 'ns-name
               selected-ns (get (ns-aliases *ns*) selected-symbol selected-symbol)]
           (str selected-ns ":\n"
                " " (:doc (meta (the-ns selected-ns))) "\n"
                (clojure.string/join
                  "\n"
                  (for [s (clojure.repl/dir-fn selected-ns)
                        :let [m (-> (str selected-ns "/" s) symbol find-var meta)]]
                    (str "---------------------------\n"
                         (:name m) "\n"
                         (cond
                           (:forms m) (->> (:forms m)
                                           (map #(str "  " (pr-str %)))
                                           (clojure.string/join "\n"))
                           (:arglists m) (pr-str (:arglists m)))
                         "\n  " (:doc m)))))))
      str
      (str/replace #"ns-name" ns-name)))


(defn list-ns-vars-with-docs
  "Lists all the vars with their documentation in the selected namespace or namespace alias"
  []
  (when-let [editor (get-active-text-editor)]
    (when-let [ns-name (get-var-under-cursor editor)]
      (println {:ns-name ns-name})
      (if (self-hosted?)
        (stderr "Listing namespace functions is not yet supported in self hosted REPL.")
        (execute-code-in-ns (list-ns-vars-with-docs-code ns-name)
                            {:displayInRepl false
                             :resultHandler (once #(show-doc-result % ns-name editor))})))))

(comment
  (list-ns-vars-with-docs))


; (defn list-ns-vars-with-docs [] ((state-get :listNsVarsWithDocs)))
(defn open-file-containing-var [] ((state-get :openFileContainingVar)))
(defn remote-nrepl-focus-next [] ((state-get :remoteNreplFocusNext)))
