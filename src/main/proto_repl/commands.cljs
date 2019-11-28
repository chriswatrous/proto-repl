(ns proto-repl.commands
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor get-var-under-cursor]]
            [proto-repl.repl :as r]
            [proto-repl.ink :as ink]
            [proto-repl.views.nrepl-connection-view :as cv]))


(def ^:private lodash (js/require "lodash"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))

(defonce state (atom {}))


(defn execute-code
  "Execute the given code string in the REPL. See proto-repl.repl/execute-code for supported
  options."
  ([code] (execute-code code {}))
  ([code options]
   (some-> @state :repl (r/execute-code (str code) (or options {})))))


(defn execute-code-in-ns
  ([code] (execute-code-in-ns code {}))
  ([code options]
   (when-let [editor (.getActiveTextEditor js/atom.workspace)]
     (execute-code code (assoc options :ns (.findNsDeclaration editor-utils editor))))))


(defn info [text] (some-> @state :repl (r/info text)))
(defn stderr [text] (some-> @state :repl (r/stderr text)))
(defn stdout [text] (some-> @state :repl (r/stdout text)))
(defn doc [text] (some-> @state :repl (r/doc text)))


(defn register-code-execution-extension
  "Registers a code execution extension with the given name and callback function.

  Code execution extensions allow other Atom packages to extend Proto REPL
  by taking output from the REPL and redirecting it for other uses like
  visualization.
  Code execution extensions are triggered when the result of code execution is
  a vector with the first element is :proto-repl-code-execution-extension. The
  second element in the vector should be the name of the extension to trigger.
  The name will be used to locate the callback function. The third element in
  the vector will be passed to the callback function."
  [name callback]
  (-> @state :extensionsFeature (.registerCodeExecutionExtension name callback)))


(defn self-hosted? [] (-> @state :repl r/self-hosted?))


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
                        (-> @state :repl (r/inline-result-handler result options))
                        (execute-ranges editor (rest ranges)))})))


(defn- get-top-level-ranges [editor]
  (into [] (.getTopLevelRanges editor-utils editor)))


(defn autoeval-file
  "Turn on auto evaluation of the current file."
  []
  (let [editor (get-active-text-editor)]
    (cond (not (js/atom.config.get "proto-repl.showInlineResults"))
          (stderr "Auto Evaling is not supported unless inline results is enabled")
          editor
          (if editor.protoReplAutoEvalDisposable
            (stderr "Already auto evaling")
            (do
              (set! editor.protoReplAutoEvalDisposable
                    (.onDidStopChanging editor
                      (fn [] (.removeAll ink/Result editor)
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
  (some-> @state :repl r/clear))


(defn interrupt []
  "Interrupt the currently executing command."
  (some-> @state :repl r/interrupt))


(defn exit-repl [] (-> @state :repl r/exit))


(defn pretty-print
  "Pretty print the last value"
  []
  (execute-code (str '(do (require 'clojure.pprint) (clojure.pprint/pp)))))


(defn execute-text-entered-in-repl []
  (some-> @state :repl r/execute-entered-text))


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
            (-> @state :extensionsFeature .startExtensionRequestProcessing)
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
      (r/on-did-start
        (fn [] (-> @state :emitter (.emit "proto-repl:connected"))
               (when (.get js/atom.config "proto-repl.refreshOnReplStart")
                 ((:refreshNamespaces @state)))
               (.activate pane)))
      (r/on-did-close
        (fn [] (swap! state assoc :repl nil)
               (-> @state :emitter (.emit "proto-repl:closed"))))
      (r/on-did-stop
        (fn [] (-> @state :extensionsFeature .stopExtensionRequestProcessing)
               (-> @state :emitter (.emit "proto-repl:stopped")))))))

(defn toggle
  "Start the REPL if it's not currently running."
  ([] (toggle nil))
  ([project-path]
   (when-not (:repl @state)
     (let [repl (r/make-repl (:extensionsFeature @state))]
       (prepare-repl repl)
       (r/start-process-if-not-running repl project-path)
       (swap! state assoc :repl repl)))))

(defn toggle-current-editor-dir
  "Start the REPL in the directory of the file in the current editor."
  []
  (some-> (get-active-text-editor) .getPath dirname toggle))

(defn start-self-hosted-repl []
  (when-not (:repl @state)
    (let [repl (r/make-repl (:extensionsFeature @state))]
      (prepare-repl repl)
      (swap! state assoc :repl repl)
      (r/start-self-hosted-connection repl))))

;; Remote NREPL connection ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private connection-view (atom nil))


(defn- handle-remote-nrepl-connection [params]
  (when-not (:repl @state)
    (let [repl (r/make-repl (:extensionsFeature @state))]
      (prepare-repl repl)
      (r/start-remote-repl-connection repl params)
      (reset! connection-view nil)
      (swap! state assoc :repl repl))))


(defn remote-nrepl-connection
  "Open the nRepl connection dialog."
  []
  (reset! connection-view (cv/show-connection-view handle-remote-nrepl-connection)))


(defn remote-nrepl-focus-next []
  (some-> @connection-view cv/toggle-focus))

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


(defn run-all-tests []
  ((:runAllTests @state))
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


(defn- parse-doc-result [value]
  (remove-dashes (if (self-hosted?) value (edn/read-string value))))


(defn- show-doc-result-inline [result var-name editor]
  (when (js/atom.config.get "proto-repl.showInlineResults")
    (let [range (doto (.getSelectedBufferRange editor)
                  (lodash.set "end.column" ##Inf))
          handler (-> @state
                      :repl
                      (r/make-inline-handler editor range
                                             (fn [v] #js [var-name nil [(parse-doc-result v)]])))]
      (handler result))))


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


(defn open-file-containing-var-code [var-name]
  (->
    '(do
       (require 'clojure.repl)
       (require 'clojure.java.shell)
       (require 'clojure.java.io)
       (import [java.io File])
       (import [java.util.jar JarFile])
       (let [path-endings
             (fn [path]
               (let [path (str/replace path #"^/" "")]
                 (cons path (lazy-seq (let [next-path (-> path (str/split #"/" 2) (get 1))]
                                        (if (seq next-path) (path-endings next-path) nil))))))
             get-resource
             (fn [file]
               (-> file java.io.File. .toURI .getPath path-endings
                   (->> (keep #(.getResource (clojure.lang.RT/baseLoader) %))
                        first)))
             resource->file-path
             (fn [res]
               (when res
                 (let [uri (-> res .toURI .normalize)]
                   (if (.isOpaque uri)
                     (let [url (.toURL uri)
                           conn (.openConnection url)
                           file (java.io.File. (.. conn getJarFileURL toURI))]
                       (str (.getAbsolutePath file) "!"
                            (second (clojure.string/split (.getPath url) #"!"))))
                     (.getAbsolutePath (java.io.File. uri))))))
             var-sym 'var-name
             the-var (-> (get (ns-aliases *ns*) var-sym)
                         (or (find-ns var-sym))
                         (some->> clojure.repl/dir-fn
                                  first
                                  name
                                  (str (name var-sym) "/")
                                  symbol)
                         (or var-sym))
             {:keys [file line protocol]} (meta (eval `(var ~the-var)))
             _ (when (and (nil? file) protocol)
                 (throw (Exception. (str "The var " var-sym " is part of a protocol which "
                                         "Proto REPL is currently unable to open."))))
             res (get-resource file)
             file-path (resource->file-path res)]
         (if-let [[_ jar-path partial-jar-path within-file-path]
                  (re-find #"(.+\.m2.repository.(.+\.jar))!/(.+)" file-path)]
           (let [decompressed-path (-> (str (System/getProperty "user.home")
                                            "/.lein/tmp-atom-jars/" partial-jar-path)
                                       File. .getAbsolutePath)
                 decompressed-file-path (.getAbsolutePath
                                          (File. decompressed-path within-file-path))
                 decompressed-path-dir (clojure.java.io/file decompressed-path)]
             (when-not (.exists decompressed-path-dir)
               (println "decompressing" jar-path "to" decompressed-path)
               (.mkdirs decompressed-path-dir)
               (let [jar-file (JarFile. jar-path)]
                 (run! (fn [jar-entry]
                         (let [file (File. decompressed-path (.getName jar-entry))]
                           (when-not (.isDirectory jar-entry)
                             (.mkdirs (.getParentFile file))
                             (with-open [is (.getInputStream jar-file jar-entry)]
                               (clojure.java.io/copy is file)))))
                       (seq (.toArray (.stream jar-file))))))
             [decompressed-file-path line])
           [file-path line])))
    str
    (str/replace #"var-name" var-name)))


(defn- handle-open-file-result [result]
  (if result.value
    (do
      (info (str "Opening " result.value))
      (let [[file line] (edn/read-string result.value)
            file (str/replace file #"%20" " ")]
        (.open js/atom.workspace file #js {:initialLine (- line 1)
                                           :searchAllPanes true})))))


(defn open-file-containing-var
  "Opens the file containing the currently selected var or namespace in the
  REPL. If the file is located inside of a jar file it will decompress the
  jar file then open it. It will first check to see if a jar file has already
  been decompressed once to avoid doing it multiple times for the same library."
  []
  (when-let [editor (get-active-text-editor)]
    (when-let [var-name (get-var-under-cursor editor)]
      (if (self-hosted?)
        (stderr "Opening files containing vars is not yet supported in self hosted REPL.")
        (execute-code-in-ns (open-file-containing-var-code var-name)
                            {:displayInRepl false
                             :resultHandler (once handle-open-file-result)})))))
