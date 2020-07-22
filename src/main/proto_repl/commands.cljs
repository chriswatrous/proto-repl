(ns proto-repl.commands
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async]
            ["atom" :refer [Emitter]]
            ["path" :refer [dirname]]
            [proto-repl.repl :as r]
            [proto-repl.ink :as ink]
            [proto-repl.views.nrepl-connection-view :as cv]
            [proto-repl.macros :refer-macros [go-try-log dochan! when-let+ template-fill]]
            [proto-repl.editor-utils :refer [get-active-text-editor
                                             get-ns-from-declaration
                                             get-cursor-in-block-range]]
            [proto-repl.utils :refer [get-config
                                      safe-async-transduce
                                      swap-config!
                                      wrap-reducer-try-log]]
            [proto-repl.repl-client.nrepl-client :as nrepl]))


(def ^:private lodash (js/require "lodash"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defonce ^:private emitter (Emitter.))
(defonce ^:private repl (atom nil))


(defn running? [] (some-> repl deref r/running?))
(defn get-nrepl-client [] (some-> repl deref :new-connection deref))
(defn info [text] (some-> @repl (r/info text)))
(defn stderr [text] (some-> @repl (r/stderr text)))
(defn stdout [text] (some-> @repl (r/stdout text)))
(defn doc [text] (some-> @repl (r/doc text)))


(defn nrepl-request [msg]
  (some-> (get-nrepl-client)
          (nrepl/request msg)))


(defn new-eval-code [options]
  (some->> (nrepl-request (assoc options :op "eval"))
           (safe-async-transduce
             cat
             (fn [result [k v]]
               (case k
                 :out (do (stdout v) result)
                 :err (do (stderr v) result)
                 :value (assoc result :value v)
                 result))
             {})))


(defn get-var-under-cursor
  ([] (some-> (get-active-text-editor) get-var-under-cursor))
  ([editor]
   (or (not-empty (.getWordUnderCursor editor #js {:wordRegex #"[a-zA-Z0-9\-.$!?\/><*=_:]+"}))
       (do (stderr "This command requires you to position the cursor on a Clojure var.")
           nil))))


(defn- flash-highlight-range [editor range]
  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor marker #js {:type "highlight" :class "block-execution"})
    (js/setTimeout #(.destroy marker) 350)))


(defn external-eval [code]
  (r/eval-and-display @repl {:code code}))


(defn execute-block
  ([{:keys [top-level]}]
   (when-let+ [editor (get-active-text-editor)
               range (get-cursor-in-block-range {:editor editor :top-level top-level})]
     (flash-highlight-range editor range)
     (r/eval-and-display @repl
                         {:code (-> editor (.getTextInBufferRange range) str/trim)
                          :ns (get-ns-from-declaration editor)
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
                          :ns (get-ns-from-declaration editor)
                          :editor editor
                          :range range}))))


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
    (some-> (new-eval-code {:code refresh-namespaces-code})
            <! :value info)))


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
      (info "Refresh complete")
      (stderr "Refresh failed"))))


;;;; Repl starting commands ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- prepare-repl [r]
  (let [pane (.getActivePane js/atom.workspace)]
    (r/on-did-start r (fn [] (.emit emitter "proto-repl:connected")
                             (when (get-config :refresh-on-repl-start)
                               (refresh-namespaces))
                             (.activate pane)))
    (r/on-did-close r (fn [] (reset! repl nil)
                             (.emit emitter "proto-repl:closed")))
    (r/on-did-stop r #(.emit emitter "proto-repl:stopped"))))


;; Remote NREPL connection ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defonce ^:private connection-view (atom nil))


(defn- handle-remote-nrepl-connection [params]
  (when-not @repl
    (let [r (r/make-repl)]
      (prepare-repl r)
      (reset! connection-view nil)
      (reset! repl r)))
  (r/start-remote-repl-connection @repl params))


(defn remote-nrepl-connection "Open the nRepl connection dialog." []
  (if (running?)
    (do (r/stderr @repl "Already connected.")
        (r/show-connection-info @repl))
    (reset! connection-view (cv/show-connection-view handle-remote-nrepl-connection))))


(defn remote-nrepl-focus-next []
  (some-> @connection-view cv/toggle-focus))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn toggle-auto-scroll [] (swap-config! :auto-scroll not))


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
                                     :ns (get-ns-from-declaration)})))))


(defn run-test-under-cursor []
  (go-try-log
    (when-let [test-name (get-var-under-cursor)]
      (when (get-config :refresh-before-running-single-test)
        (<! (refresh-namespaces)))
      (<! (r/eval-and-display @repl
                              {:code (str "(time (clojure.test/test-vars [#'" test-name "]))")
                               :ns (get-ns-from-declaration)})))))


(defn run-all-tests []
  (go-try-log
    (<! (refresh-namespaces))
    (<! (r/eval-and-display @repl {:code '(time (clojure.test/run-all-tests))}))))


; (defn- show-doc-result-inline [result var-name editor]
;   (when (get-config :show-inline-results)))
;   (when true
;     (let [range (doto (.getSelectedBufferRange editor)
;                   (lodash.set "end.column" ##Inf))
;           handler (r/make-inline-handler @repl editor range
;                                          (fn [v] #js [var-name nil [(parse-doc-result v)]]))]
;       (handler result))))


(defn- run-doc-like-command [code]
  (go-try-log
    (let [result (-> (new-eval-code {:code code :ns (get-ns-from-declaration)})
                     <! :value edn/read-string)]
      (if-let [error (:error result)]
        (stderr error)
        (doc result)))))


(defn- get-doc-code [var-name]
  (template-fill [var-name]
    (require 'clojure.repl
             'clojure.string)
    (-> var-name
        clojure.repl/doc
        with-out-str
        (clojure.string/replace #"^-+\n" "")
        not-empty
        (or {:error "doc not found: var-name"}))))


(defn print-var-documentation []
  (some-> (get-var-under-cursor) get-doc-code run-doc-like-command))


(defn get-code-code [var-name]
  (template-fill [var-name]
    (require 'clojure.repl)
    (if-let [v (resolve 'var-name)]
      (if (instance? java.lang.Class v)
        {:error "This command doesn't work on Java classes."}
        (with-out-str (clojure.repl/source var-name)))
      {:error "var not found: var-name"})))


(defn print-var-code []
  (some-> (get-var-under-cursor) get-code-code run-doc-like-command))


(defn- list-ns-vars-code [ns-name]
  (template-fill [ns-name]
    (require 'clojure.repl
             'clojure.string)
    (if-let [ns (or ((ns-aliases *ns*) 'ns-name)
                    (find-ns 'ns-name))]
      (str "Vars in " ns ":\n"
           "------------------------------\n"
           (clojure.string/join "\n" (clojure.repl/dir-fn ns)))
      {:error "namespace not found: ns-name"})))


(defn list-ns-vars "Lists all the vars in the selected namespace or namespace alias" []
  (some-> (get-var-under-cursor) list-ns-vars-code run-doc-like-command))


(defn list-ns-vars-with-docs-code [ns-name]
  (template-fill [ns-name]
    (require 'clojure.repl
             'clojure.string)
    (if-let [ns (or ((ns-aliases *ns*) 'ns-name)
                    (find-ns 'ns-name))]
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
                (clojure.string/join "\n")))
      {:error "namespace not found: ns-name"})))


(defn list-ns-vars-with-docs
  "Lists all the vars with their documentation in the selected namespace or namespace alias"
  []
  (some-> (get-var-under-cursor) list-ns-vars-with-docs-code run-doc-like-command))


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
    (when-let+ [var-name (get-var-under-cursor)
                value (:value (<! (new-eval-code {:code (open-file-containing-var-code var-name)
                                                  :ns (get-ns-from-declaration)})))
                [file line] (edn/read-string value)
                file (str/replace file #"%20" " ")]
      (.open js/atom.workspace file (js-obj "initialLine" (dec line)
                                            "searchAllPanes" true)))))
