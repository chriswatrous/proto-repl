(ns proto-repl.commands
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            ["atom" :refer [Emitter]]
            ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor]]
            [proto-repl.repl :as r]
            [proto-repl.ink :as ink]
            [proto-repl.views.nrepl-connection-view :as cv]
            [proto-repl.utils :refer [template-replace]]))


(def ^:private lodash (js/require "lodash"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))
(def ^:private ExtensionsFeature (js/require "../lib/features/extensions-feature"))

(defonce ^:private emitter (Emitter.))
(defonce ^:private extensions-feature (ExtensionsFeature.))
(defonce ^:private repl (atom nil))

(defn execute-code
  "Execute the given code string in the REPL. See proto-repl.repl/execute-code for supported
  options."
  ([code] (execute-code code nil))
  ([code options] (some-> @repl (r/execute-code (str code) (or options {})))))


(defn execute-code-in-ns
  ([code] (execute-code-in-ns code {}))
  ([code options]
   (when-let [editor (get-active-text-editor)]
     (execute-code code (assoc options :ns (.findNsDeclaration editor-utils editor))))))


(defn info [text] (some-> @repl (r/info text)))
(defn stderr [text] (some-> @repl (r/stderr text)))
(defn stdout [text] (some-> @repl (r/stdout text)))
(defn doc [text] (some-> @repl (r/doc text)))


(defn get-var-under-cursor [editor]
  (let [word (.getWordUnderCursor editor #js {:wordRegex #"[a-zA-Z0-9\-.$!?\/><*=_:]+"})]
    (if (seq word)
      word
      (do
        (proto-repl.commands/stderr
          "This command requires you to position the cursor on a Clojure var.")
        nil))))


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
  (.registerCodeExecutionExtension extensions-feature name callback))


(defn- flash-highlight-range [editor range]
  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor marker #js {:type "highlight" :class "block-execution"})
    (js/setTimeout #(.destroy marker) 350)))

(defn execute-block
  ([] (execute-block {}))
  ([options]
   (when-let [editor (get-active-text-editor)]
     (when-let [range (.getCursorInBlockRange editor-utils editor (clj->js options))]
       (flash-highlight-range editor range)
       (let [text (-> editor (.getTextInBufferRange range) str/trim)
             options (assoc options :displayCode text
                                    :inlineOptions {:editor editor
                                                    :range range})]
         (execute-code-in-ns text options))))))


(defn execute-selected-text "Executes the selected code."
  ([] (execute-selected-text {}))
  ([options]
   (when-let [editor (get-active-text-editor)]
     (let [text (or (not-empty (.getSelectedText editor))
                    (get-var-under-cursor editor))
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
                        (-> @repl (r/inline-result-handler result options))
                        (execute-ranges editor (rest ranges)))})))


(defn- get-top-level-ranges [editor]
  (into [] (.getTopLevelRanges editor-utils editor)))


(defn autoeval-file "Turn on auto evaluation of the current file." []
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


(defn stop-autoeval-file "Turns off autoevaling of the current file." []
  (when-let [editor (get-active-text-editor)]
    (when editor.protoReplAutoEvalDisposable
      (.dispose editor.protoReplAutoEvalDisposable)
      (set! editor.protoReplAutoEvalDisposable nil))))


(defn clear-repl []
  (some-> @repl r/clear))


(defn interrupt "Interrupt the currently executing command." []
  (some-> @repl r/interrupt))


(defn exit-repl [] (-> @repl r/exit))


(defn pretty-print "Pretty print the last value" []
  (execute-code (str '(do (require 'clojure.pprint) (clojure.pprint/pp)))))


(defn execute-text-entered-in-repl []
  (some-> @repl r/execute-entered-text))


(def ^:private refresh-namespaces-code
  '(do
     (try
       (require 'user)
       (catch java.io.FileNotFoundException e
         (println "No user namespace defined. Defaulting to"
                  "clojure.tools.namespace.repl/refresh.")))
     (try
       (require 'clojure.tools.namespace.repl)
       (catch java.io.FileNotFoundException e
         (println "clojure.tools.namespace.repl not available. Add proto-repl in your project.clj"
                  "as a dependency to allow refresh. See https://clojars.org/proto-repl")))
     (let [refresh (or (find-var 'user/reset) (find-var 'clojure.tools.namespace.repl/refresh))
           result
           (if refresh
             (refresh)
             (println "You can use your own refresh function, just define reset function in user"
                      "namespace\n"
                      "See this"
                      "https://github.com/clojure/tools.namespace#reloading-code-motivation"
                      "for why you should use it."))]
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
            (.startExtensionRequestProcessing extensions-feature)
            (when callback (callback)))
        result.error
        (stderr (str "Refresh Warning: " result.error))))


(defn refresh-namespaces
  "Refresh any changed code in the project since the last refresh. Presumes
  clojure.tools.namespace is a dependency and setup with standard user/reset
  function. Will invoke the optional callback if refresh is successful."
  ([] (refresh-namespaces nil))
  ([callback]
   (info "Refreshing code...")
   (execute-code refresh-namespaces-code
                 {:displayInRepl false
                  :resultHandler #(refresh-result-handler % callback)})))


(defn super-refresh-namespaces
  "Refresh all of the code in the project whether it has changed or not.
  Presumes clojure.tools.namespace is a dependency and setup with standard
  user/reset function. Will invoke the optional callback if refresh is
  successful."
  ([] (super-refresh-namespaces nil))
  ([callback]
   (info "Refreshing code...")
   (execute-code '(when (find-ns 'clojure.tools.namespace.repl)
                    (eval '(clojure.tools.namespace.repl/clear)))
                 {:displayInRepl false
                  :resultHandler #(refresh-result-handler % callback)})))

;;;; Repl starting commands ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- prepare-repl [r]
  (let [pane (.getActivePane js/atom.workspace)]
    (r/on-did-start r
      (fn [] (.emit emitter "proto-repl:connected")
             (when (.get js/atom.config "proto-repl.refreshOnReplStart")
               (refresh-namespaces))
             (.activate pane)))
    (r/on-did-close r
      (fn [] (reset! repl nil)
             (.emit emitter "proto-repl:closed")))
    (r/on-did-stop r
      (fn [] (.stopExtensionRequestProcessing extensions-feature)
             (.emit emitter "proto-repl:stopped")))))

;; Remote NREPL connection ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private connection-view (atom nil))


(defn- handle-remote-nrepl-connection [params]
  (when-not @repl
    (let [r (r/make-repl extensions-feature)]
      (prepare-repl r)
      (r/start-remote-repl-connection r params)
      (reset! connection-view nil)
      (reset! repl r))))


(defn remote-nrepl-connection "Open the nRepl connection dialog." []
  (reset! connection-view (cv/show-connection-view handle-remote-nrepl-connection)))


(defn remote-nrepl-focus-next []
  (some-> @connection-view cv/toggle-focus))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-auto-scroll []
  (let [key "proto-repl.autoScroll"]
    (.set js/atom.config key (not (.get js/atom.config key)))))


(defn load-current-file []
  (when-let [editor (get-active-text-editor)]
    (let [filename (.getPath editor)]
      (execute-code `(do (~'println "Loading File " ~filename)
                         (~'load-file ~filename))))))


(defn run-tests-in-namespace []
  (when-let [editor (get-active-text-editor)]
    (let [run #(execute-code-in-ns '(clojure.test/run-tests))]
      (if (js/atom.config.get "proto-repl.refreshBeforeRunningTestFile")
        (refresh-namespaces run)
        (run)))))

(defn ^:private run-test-var-code [test-name]
  (template-replace
    '(do (clojure.test/test-vars [#'--test-name--])
         (clojure.core/println "tested" '--test-name--))
    {:test-name test-name}))

(defn run-test-under-cursor []
  (when-let [test-name (some-> (get-active-text-editor) get-var-under-cursor symbol)]
    (let [run #(execute-code-in-ns (run-test-var-code test-name))]
      (if (js/atom.config.get "proto-repl.refreshBeforeRunningSingleTest")
        (refresh-namespaces run)
        (run)))))

(defn run-all-tests []
  (refresh-namespaces
    #(execute-code '(def all-tests-future (future (time (clojure.test/run-all-tests)))))))


(defn- get-doc-code [var-name]
  (template-replace
    '(do (require 'clojure.repl)
         (with-out-str (clojure.repl/doc --var-name--)))
    {:var-name var-name}))


(defn- parse-doc-result [value]
  (-> value edn/read-string (str/replace #"^-+\n" "")))


(defn- show-doc-result-inline [result var-name editor]
  (when (js/atom.config.get "proto-repl.showInlineResults")
    (let [range (doto (.getSelectedBufferRange editor)
                  (lodash.set "end.column" ##Inf))
          handler (r/make-inline-handler @repl editor range
                                         (fn [v] #js [var-name nil [(parse-doc-result v)]]))]
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
      (execute-code-in-ns (template-replace
                            '(do (require 'clojure.repl)
                                 (with-out-str (clojure.repl/source --var-name--)))
                            {:var-name var-name})
                          {:displayInRepl false
                           :resultHandler (once #(show-doc-result % var-name editor))}))))


(defn- list-ns-vars-code [ns-name]
  (template-replace
    '(do
       (require 'clojure.repl)
       (let [selected-symbol '--ns-name--
             selected-ns (get (ns-aliases *ns*) selected-symbol selected-symbol)]
         (str "Vars in " selected-ns ":\n"
              "------------------------------\n"
              (str/join "\n" (clojure.repl/dir-fn selected-ns)))))
    {:ns-name ns-name}))


(defn list-ns-vars "Lists all the vars in the selected namespace or namespace alias" []
  (when-let [editor (get-active-text-editor)]
    (when-let [ns-name (get-var-under-cursor editor)]
      (println {:ns-name ns-name})
      (execute-code-in-ns (list-ns-vars-code ns-name)
                          {:displayInRepl false
                           :resultHandler (once #(show-doc-result % ns-name editor))}))))


(defn list-ns-vars-with-docs-code [ns-name]
  (template-replace
    '(do
       (require 'clojure.repl)
       (require 'clojure.string)
       (let [selected-symbol '--ns-name--
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
    {:ns-name ns-name}))


(defn list-ns-vars-with-docs
  "Lists all the vars with their documentation in the selected namespace or namespace alias"
  []
  (when-let [editor (get-active-text-editor)]
    (when-let [ns-name (get-var-under-cursor editor)]
      (println {:ns-name ns-name})
      (execute-code-in-ns (list-ns-vars-with-docs-code ns-name)
                          {:displayInRepl false
                           :resultHandler (once #(show-doc-result % ns-name editor))}))))

(defn- open-file-containing-var-code [var-name]
  (template-replace
    '(do
       (require 'clojure.repl)
       (require 'clojure.java.shell)
       (require 'clojure.java.io)
       (let [path-endings
             (fn [path]
               (let [parts (-> path (clojure.string/replace #"^/" "") (clojure.string/split #"/"))]
                 (map #(clojure.string/join "/" (drop % parts))
                      (range (count parts)))))
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
             var-sym '--var-name--
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
                                       java.io.File.
                                       .getAbsolutePath)
                 decompressed-file-path (.getAbsolutePath
                                          (java.io.File. decompressed-path within-file-path))
                 decompressed-path-dir (clojure.java.io/file decompressed-path)]
             (when-not (.exists decompressed-path-dir)
               (println "decompressing" jar-path "to" decompressed-path)
               (.mkdirs decompressed-path-dir)
               (let [jar-file (java.util.jar.JarFile. jar-path)]
                 (run! (fn [jar-entry]
                         (let [file (java.io.File. decompressed-path (.getName jar-entry))]
                           (when-not (.isDirectory jar-entry)
                             (.mkdirs (.getParentFile file))
                             (with-open [is (.getInputStream jar-file jar-entry)]
                               (clojure.java.io/copy is file)))))
                       (seq (.toArray (.stream jar-file))))))
             [decompressed-file-path line])
           [file-path line])))
    {:var-name var-name}))


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
  (when-let [var-name (some-> (get-active-text-editor) get-var-under-cursor)]
    (execute-code-in-ns (open-file-containing-var-code var-name)
                        {:displayInRepl false
                         :resultHandler (once handle-open-file-result)})))
