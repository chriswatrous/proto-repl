(ns proto-repl.commands
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async]
            ["atom" :refer [Emitter]]
            ["path" :refer [dirname]]
            [proto-repl.editor-utils :refer [get-active-text-editor]]
            [proto-repl.repl :as r]
            [proto-repl.ink :as ink]
            [proto-repl.views.nrepl-connection-view :as cv]
            [proto-repl.macros :refer-macros [go-try-log dochan! when-let+ template-fill]]
            [proto-repl.utils :refer [get-config safe-async-transduce wrap-reducer-try-log]]
            [proto-repl.repl-client.nrepl-client :as nrepl]))

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


(defn get-nrepl-client [] @(:new-connection @repl))


(defn info [text] (some-> @repl (r/info text)))
(defn stderr [text] (some-> @repl (r/stderr text)))
(defn stdout [text] (some-> @repl (r/stdout text)))
(defn doc [text] (some-> @repl (r/doc text)))


(defn- get-current-ns []
  (some->> (get-active-text-editor)
           (.findNsDeclaration editor-utils)))


(defn new-eval-code [options]
  (when-let [new-connection @(:new-connection @repl)]
    (safe-async-transduce
      cat
      (fn [result [k v]]
        (case k
          :out (do (stdout v) result)
          :err (do (stderr v) result)
          :value (assoc result :value v)
          result))
      {}
      (nrepl/request new-connection (assoc options :op "eval")))))


(defn get-var-under-cursor [editor]
  (or (not-empty (.getWordUnderCursor editor #js {:wordRegex #"[a-zA-Z0-9\-.$!?\/><*=_:]+"}))
      (do (stderr "This command requires you to position the cursor on a Clojure var.")
          nil)))


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
  ([{:keys [top-level]}]
   (when-let+ [editor (get-active-text-editor)
               range (.getCursorInBlockRange editor-utils editor #js {:topLevel top-level})]
     (flash-highlight-range editor range)
     (r/eval-and-display @repl
                         {:code (-> editor (.getTextInBufferRange range) str/trim)
                          :ns (.findNsDeclaration editor-utils editor)
                          :editor editor
                          :range range}))))


(defn execute-selected-text "Executes the selected code."
  ([] (execute-selected-text {}))
  ([options]
   (when-let+ [editor (get-active-text-editor)
               range (.getSelectedBufferRange editor)]
     (when (lodash.isEqual range.start range.end)
       (set! range.end.column ##Inf))
     (r/eval-and-display @repl
                         {:code (or (not-empty (.getSelectedText editor))
                                    (get-var-under-cursor editor))
                          :ns (.findNsDeclaration editor-utils editor)
                          :editor editor
                          :range range}))))


(defn- get-top-level-ranges [editor]
  (into [] (.getTopLevelRanges editor-utils editor)))


(defn clear-repl []
  (some-> @repl r/clear))


(defn interrupt "Interrupt the currently executing command." []
  (some-> @repl r/interrupt))


(defn exit-repl [] (-> @repl r/exit))


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


(defn refresh-namespaces
  "Refresh any changed code in the project since the last refresh. Presumes
  clojure.tools.namespace is a dependency and setup with standard user/reset
  function."
  []
  (println "refresh-namespaces")
  (go-try-log
    (info "Refreshing code...")
    (let [{:keys [value]} (<! (new-eval-code {:code refresh-namespaces-code}))]
      (when value
        (info value)
        (.startExtensionRequestProcessing extensions-feature)))))


(defn super-refresh-namespaces
  "Refresh all of the code in the project whether it has changed or not.
  Presumes clojure.tools.namespace is a dependency and setup with standard
  user/reset function. Will invoke the optional callback if refresh is
  successful."
  []
  (go-try-log
    (info "Refreshing code...")
    (if-let [value (-> (new-eval-code
                         {:code '(when (find-ns 'clojure.tools.namespace.repl)
                                   (eval '(clojure.tools.namespace.repl/clear)))})
                       <! :value)]
      (do (info "Refresh complete")
          (.startExtensionRequestProcessing extensions-feature))
      (stderr "Refresh failed"))))


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
  (go-try-log
    (when-let [filename (some-> (get-active-text-editor) .getPath)]
      (new-eval-code {:code (template-fill [filename]
                              (println "Loading File " "filename")
                              (load-file "filename"))}))))


(defn run-tests-in-namespace []
  (go-try-log
    (when-let [editor (get-active-text-editor)]
      (when (get-config :refresh-before-running-test-file)
        (<! (refresh-namespaces)))
      (<! (r/eval-and-display @repl {:code '(time (clojure.test/run-tests))
                                     :ns (get-current-ns)})))))


(defn run-test-under-cursor []
  (go-try-log
    (when-let [test-name (some-> (get-active-text-editor) get-var-under-cursor)]
      (when (get-config :refresh-before-running-single-test)
        (<! (refresh-namespaces)))
      (<! (r/eval-and-display @repl
                              {:code (str "(time (clojure.test/test-vars [#'" test-name "]))")
                               :ns (get-current-ns)})))))


(defn run-all-tests []
  (go-try-log
    (<! (refresh-namespaces))
    (<! (r/eval-and-display @repl {:code '(time (clojure.test/run-all-tests))}))))


(defn- get-doc-code [var-name]
  (template-fill [var-name]
    (require 'clojure.repl)
    (with-out-str (clojure.repl/doc var-name))))


(defn- parse-doc-result [value]
  (-> value edn/read-string (str/replace #"^-+\n" "")))


; (defn- show-doc-result-inline [result var-name editor]
;   (when (js/atom.config.get "proto-repl.showInlineResults"))
;   (when true
;     (let [range (doto (.getSelectedBufferRange editor)
;                   (lodash.set "end.column" ##Inf))
;           handler (r/make-inline-handler @repl editor range
;                                          (fn [v] #js [var-name nil [(parse-doc-result v)]]))]
;       (handler result))))


(defn- show-doc-result-in-console [value var-name]
  (if-let [msg (not-empty (str/trim (parse-doc-result value)))]
    (doc msg)
    (stderr (str "No doc found for " var-name))))


(defn- show-doc-result [value var-name editor]
  (show-doc-result-in-console value var-name))
  ; (show-doc-result-inline value var-name editor))


(defn- run-doc-like-command [editor name code]
  (go-try-log
    (-> (new-eval-code {:code code :ns (get-current-ns)})
        <! :value
        (show-doc-result name editor))))


(defn print-var-documentation []
  (when-let+ [editor (get-active-text-editor)
              var-name (get-var-under-cursor editor)]
    (run-doc-like-command editor var-name (get-doc-code var-name))))


(defn print-var-code []
  (when-let+ [editor (get-active-text-editor)
              var-name (get-var-under-cursor editor)]
    (run-doc-like-command editor var-name (template-fill [var-name]
                                            (require 'clojure.repl)
                                            (with-out-str (clojure.repl/source var-name))))))


(defn- list-ns-vars-code [ns-name]
  (template-fill [ns-name]
    (require 'clojure.repl
             'clojure.string)
    (let [ns-sym 'ns-name
          ns (or ((ns-aliases *ns*) ns-sym)
                 (find-ns ns-sym))]
      (when-not ns
        (throw (Exception. (str "Namespace not found: " ns-sym))))
      (str "Vars in " ns ":\n"
           "------------------------------\n"
           (clojure.string/join "\n" (clojure.repl/dir-fn ns))))))


(defn list-ns-vars "Lists all the vars in the selected namespace or namespace alias" []
  (when-let+ [editor (get-active-text-editor)
              ns-name (get-var-under-cursor editor)]
    (run-doc-like-command editor ns-name (list-ns-vars-code ns-name))))


(defn list-ns-vars-with-docs-code [ns-name]
  (template-fill [ns-name]
    (require 'clojure.repl
             'clojure.string)
    (let [ns-sym 'ns-name
          ns (or ((ns-aliases *ns*) ns-sym)
                 (find-ns ns-sym))]
      (when-not ns
        (throw (Exception. (str "Namespace not found: " ns-sym))))
      (str ns ":\n"
           " " (:doc (meta ns)) "\n"
           (->> (clojure.repl/dir-fn ns)
                (map (fn [var-sym]
                       (let [{:keys [name forms arglists doc]}
                             (-> (str ns "/" var-sym) symbol find-var meta)]
                         (str "---------------------------\n"
                              name "\n"
                              (cond
                                forms (->> forms
                                           (map #(str "  " (pr-str %)))
                                           (clojure.string/join "\n"))
                                arglists (pr-str arglists))
                              "\n  " doc))))
                (clojure.string/join "\n"))))))


(defn list-ns-vars-with-docs
  "Lists all the vars with their documentation in the selected namespace or namespace alias"
  []
  (when-let+ [editor (get-active-text-editor)
              ns-name (get-var-under-cursor editor)]
    (run-doc-like-command editor ns-name (list-ns-vars-with-docs-code ns-name))))


(defn- open-file-containing-var-code [var-name]
  (template-fill [var-name]
    (require 'clojure.repl
             'clojure.java.shell
             'clojure.java.io)
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
        [file-path line]))))


(defn open-file-containing-var
  "Opens the file containing the currently selected var or namespace in the
  REPL. If the file is located inside of a jar file it will decompress the
  jar file then open it. It will first check to see if a jar file has already
  been decompressed once to avoid doing it multiple times for the same library."
  []
  (go-try-log
    (when-let+ [var-name (some-> (get-active-text-editor) get-var-under-cursor)
                value (:value (<! (new-eval-code {:code (open-file-containing-var-code var-name)
                                                  :ns (get-current-ns)})))
                [file line] (edn/read-string value)
                file (str/replace file #"%20" " ")]
      (.open js/atom.workspace file (js-obj "initialLine" (dec line)
                                            "searchAllPanes" true)))))
