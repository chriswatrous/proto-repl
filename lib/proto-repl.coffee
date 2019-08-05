# Load t(proto-repl.saved-values/save 1)he ClojureScript code
# Rebuild it with lein cljsbuild once.
edn_reader = require './proto_repl/edn_reader.js'

{CompositeDisposable, Range, Point, Emitter} = require 'atom'
NReplConnectionView = require './views/nrepl-connection-view'
Repl = require './repl'
url = require 'url'
path = require 'path'
EditorUtils = require './editor-utils'
SaveRecallFeature = require './features/save-recall-feature'
ExtensionsFeature = require './features/extensions-feature'
CompletionProvider = require './completion-provider'

module.exports =
  subscriptions: null
  repl: null
  toolbar: null
  ink: null
  EditorUtils: EditorUtils
  edn_reader: edn_reader

  saveRecallFeature: null
  extensionsFeature: null

  # refreshNamespacesCommand:
  #   "(do
  #     (try
  #       (require 'user)
  #       (catch java.io.FileNotFoundException e
  #         (println (str \"No user namespace defined. Defaulting to clojure.tools.namespace.repl/refresh.\\n\"))))
  #     (try
  #       (require 'clojure.tools.namespace.repl)
  #       (catch java.io.FileNotFoundException e
  #         (println \"clojure.tools.namespace.repl not available. Add proto-repl in your project.clj as a dependency to allow refresh. See https://clojars.org/proto-repl\")))
  #     (let [user-reset 'user/reset
  #           ctnr-refresh 'clojure.tools.namespace.repl/refresh
  #           result (cond
  #                    (find-var user-reset)
  #                    ((resolve user-reset))
  #
  #                    (find-var ctnr-refresh)
  #                    ((resolve ctnr-refresh))
  #
  #                    :else
  #                     (println (str \"You can use your own refresh function, just define reset function in user namespace\\n\"
  #                                   \"See this https://github.com/clojure/tools.namespace#reloading-code-motivation for why you should use it\")))]
  #       (when (isa? (type result) Exception)
  #         (println (.getMessage result)))
  #       result))"

  # refreshResultHandler: (callback, result)->
  #   # Value will contain an exception if it's not valid otherwise it will be nil
  #   # nil will also be returned if there is no clojure.tools.namespace available.
  #   # The callback will still be invoked in that case. That's important so that
  #   # run all tests will still work without it.
  #   if result.value
  #     window.protoRepl.info("Refresh complete")
  #     # Make sure the extension process is running after ever refresh.
  #     # If refreshing or laoding code had failed the extensions feature might not
  #     # have stopped itself.
  #     @extensionsFeature.startExtensionRequestProcessing()
  #     callback() if callback
  #   else if result.error
  #     window.protoRepl.stderr("Refresh Warning: " + result.error)

  # Refreshes any changed code in the project since the last refresh. Presumes
  # clojure.tools.namespace is a dependency and setup with standard user/reset
  # function. Will invoke the optional callback if refresh is successful.
  refreshNamespaces: (callback=null)->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Refreshing not supported in self hosted REPL.")
    else
      window.protoRepl.info("Refreshing code...\n")
      window.protoRepl.executeCode @refreshNamespacesCommand,
        displayInRepl: false,
        resultHandler: (result)=>
          @refreshResultHandler(callback, result)

  # Refreshes all of the code in the project whether it has changed or not.
  # Presumes clojure.tools.namespace is a dependency and setup with standard
  # user/reset function. Will invoke the optional callback if refresh is
  # successful.
  superRefreshNamespaces: (callback=null)->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Refreshing not supported in self hosted REPL.")
    else
      window.protoRepl.info("Clearing all and then refreshing code...\n")
      window.protoRepl.executeCode "(do
                      (when (find-ns 'clojure.tools.namespace.repl)
                        (eval '(clojure.tools.namespace.repl/clear)))
                      #{@refreshNamespacesCommand})",
        displayInRepl: false,
        resultHandler: (result)=> @refreshResultHandler(callback, result)

  loadCurrentFile: ->
    if editor = atom.workspace.getActiveTextEditor()
      if window.protoRepl.isSelfHosted()
        window.protoRepl.stderr("Loading files is not supported yet in self hosted REPL.")
      else
        # Escape file name
        fileName = editor.getPath().replace(/\\/g,"\\\\")
        window.protoRepl.executeCode("(do (println \"Loading File #{fileName}\") (load-file \"#{fileName}\"))")

  runTestsInNamespace: ->
    if editor = atom.workspace.getActiveTextEditor()
      if window.protoRepl.isSelfHosted()
        window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
      else
        code = "(clojure.test/run-tests)"
        if atom.config.get("proto-repl.refreshBeforeRunningTestFile")
          @refreshNamespaces =>
            window.protoRepl.executeCodeInNs(code)
        else
          window.protoRepl.executeCodeInNs(code)

  runTestUnderCursor: ->
    if editor = atom.workspace.getActiveTextEditor()
      if window.protoRepl.isSelfHosted()
        window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
      else
        if testName = window.protoRepl.getClojureVarUnderCursor(editor)
          code = "(do (clojure.test/test-vars [#'#{testName}]) (println \"tested #{testName}\"))"
          if atom.config.get("proto-repl.refreshBeforeRunningSingleTest")
            @refreshNamespaces =>
              window.protoRepl.executeCodeInNs(code)
          else
            window.protoRepl.executeCodeInNs(code)

  runAllTests: ->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
    else
      @refreshNamespaces =>
        # Tests are only run if the refresh is successful.
        window.protoRepl.executeCode("(def all-tests-future (future (time (clojure.test/run-all-tests))))")

  printVarDocumentation: ->
    if editor = atom.workspace.getActiveTextEditor()
      if varName = window.protoRepl.getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          code = "(with-out-str (doc #{varName}))"
          parser = (value)-> value.replace(/^-+\n/, '')
        else
          code =
            "(do
               (require 'clojure.repl)
               (with-out-str (clojure.repl/doc #{varName})))"
          parser = (value)=> window.protoRepl.parseEdn(value).replace(/^-+\n/, '')

        if @ink && atom.config.get('proto-repl.showInlineResults')
          range = editor.getSelectedBufferRange()
          range.end.column = Infinity
          inlineHandler = @repl.makeInlineHandler(editor, range, (value)=>
            [varName, {a: 1}, [parser(value)]])

        handled = false

        window.protoRepl.executeCodeInNs code, displayInRepl: false, resultHandler: (value)=>
          # This seems to get called twice on error, not sure why.
          # To reproduce, try to run this command on java.lang.System.
          return if handled
          handled = true

          inlineHandler?(value)

          if value.value
            msg = parser(value.value).trim()
            if msg
              window.protoRepl.doc(msg)
            else
              window.protoRepl.stderr("No doc found for #{varName}")
          else if value.error
            window.protoRepl.stderr(value.error)
          else
            msg = 'Did not get value or error.'
            console.error(msg, value)
            throw new Error(msg)

  printVarCode: ->
    if editor = atom.workspace.getActiveTextEditor()
      if varName = window.protoRepl.getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          # code = "(source #{varName})"
          window.protoRepl.stderr("Showing source code is not yet supported in self hosted REPL.")
        else
          code = "(do (require 'clojure.repl) (clojure.repl/source #{varName}))"
          window.protoRepl.executeCodeInNs(code)

  # Lists all the vars in the selected namespace or namespace alias
  listNsVars: ->
    if editor = atom.workspace.getActiveTextEditor()
      if nsName = window.protoRepl.getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          # code = "(dir #{nsName})"
          window.protoRepl.stderr("Listing namespace functions is not yet supported in self hosted REPL.")
        else
          code = "(do
                    (require 'clojure.repl)
                    (let [selected-symbol '#{nsName}
                          selected-ns (get (ns-aliases *ns*) selected-symbol selected-symbol)]
                      (println \"\\nVars in\" (str selected-ns \":\"))
                      (println \"------------------------------\")
                      (doseq [s (clojure.repl/dir-fn selected-ns)]
                        (println s))
                      (println \"------------------------------\")))"
          window.protoRepl.executeCodeInNs(code)

  # Lists all the vars with their documentation in the selected namespace or namespace alias
  listNsVarsWithDocs: ->
    if editor = atom.workspace.getActiveTextEditor()
      if nsName = window.protoRepl.getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          # code = "(dir #{nsName})"
          window.protoRepl.stderr("Listing namespace functions is not yet supported in self hosted REPL.")
        else
          code = "(do
                    (require 'clojure.repl)
                    (let [selected-symbol '#{nsName}
                          selected-ns (get (ns-aliases *ns*) selected-symbol selected-symbol)]
                      (println (str \"\\n\" selected-ns \":\"))
                      (println \"\" (:doc (meta (the-ns selected-ns))))
                      (doseq [s (clojure.repl/dir-fn selected-ns) :let [m (-> (str selected-ns \"/\" s) symbol find-var meta)]]
                        (println \"---------------------------\")
                        (println (:name m))
                        (cond
                          (:forms m) (doseq [f (:forms m)]
                                       (print \"  \")
                                       (prn f))
                          (:arglists m) (prn (:arglists m)))
                        (println \" \" (:doc m)))
                      (println \"------------------------------\")))"

          window.protoRepl.executeCodeInNs(code)

  # Opens the file containing the currently selected var or namespace in the REPL. If the file is located
  # inside of a jar file it will decompress the jar file then open it. It will first check to see if a
  # jar file has already been decompressed once to avoid doing it multiple times for the same library.
  # Assumes that the Atom command line alias "atom" can be used to invoke Atom.
  openFileContainingVar: ->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Opening files containing vars is not yet supported in self hosted REPL.")
    else
      if editor = atom.workspace.getActiveTextEditor()
        if selected = window.protoRepl.getClojureVarUnderCursor(editor)
          text = "(do (require 'clojure.repl)
              (require 'clojure.java.shell)
              (require 'clojure.java.io)
              (import [java.io File])
              (import [java.util.jar JarFile])
              (let [var-sym '#{selected}
                    the-var (or (some->> (or (get (ns-aliases *ns*) var-sym) (find-ns var-sym))
                                         clojure.repl/dir-fn
                                         first
                                         name
                                         (str (name var-sym) \"/\")
                                         symbol)
                                var-sym)
                    {:keys [file line protocol]} (meta (eval `(var ~the-var)))
                    _ (when (and (nil? file) protocol)
                        (throw (Exception. (format \"The var %s is part of a protocol which Proto REPL is currently unable to open.\"
                                                   var-sym))))
                    file-path (loop [paths (remove empty? (clojure.string/split (.getPath (.toURI (java.io.File. file)))
                                                                                #\"/\"))]
                                (when-not (empty? paths)
                                  (let [path (clojure.string/join \"/\" paths)
                                        res (.getResource (clojure.lang.RT/baseLoader) path)]
                                    (if-not (nil? res)
                                            (let [uri (.normalize (.toURI (.getResource (clojure.lang.RT/baseLoader) path)))]
                                              (if (.isOpaque uri)
                                                (let [url (.toURL uri)
                                                      conn (.openConnection url)
                                                      file (java.io.File. (.. conn getJarFileURL toURI))]
                                                  (str (.getAbsolutePath file) \"!\" (second (clojure.string/split (.getPath url) #\"!\"))))
                                                (.getAbsolutePath (java.io.File. uri))))
                                            (recur (rest paths))))))]
                (if-let [[_
                          jar-path
                          partial-jar-path
                          within-file-path] (re-find #\"(.+\\.m2.repository.(.+\\.jar))!/(.+)\" file-path)]
                  (let [decompressed-path (.getAbsolutePath
                                           (File. (.getAbsolutePath (File.
                                                                     (System/getProperty \"user.home\")
                                                                     \"/.lein/tmp-atom-jars/\"))
                                                  partial-jar-path))
                        decompressed-file-path (.getAbsolutePath (File. decompressed-path within-file-path))
                        decompressed-path-dir (clojure.java.io/file decompressed-path)]
                    (when-not (.exists decompressed-path-dir)
                      (println \"decompressing\" jar-path \"to\" decompressed-path)
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
                  [file-path line])))"
          window.protoRepl.executeCodeInNs text,
            displayInRepl: false
            resultHandler: (result)=>
              if result.value
                window.protoRepl.info("Opening #{result.value}")
                [file, line] = window.protoRepl.parseEdn(result.value)
                file = file.replace(/%20/g, " ")
                atom.workspace.open(file, {initialLine: line-1, searchAllPanes: true})
              else
                window.protoRepl.stderr("Error trying to open: #{result.error}")

  remoteNreplFocusNext: ->
    if @connectionView?
      @connectionView.toggleFocus()
