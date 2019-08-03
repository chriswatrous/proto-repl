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

  # Executes the selected code.
  # Valid options:
  # * resultHandler - a callback function to invoke with the value that was read.
  #   If this is passed in then the value will not be displayed in the REPL.
  executeSelectedText: (options={})->
    if editor = atom.workspace.getActiveTextEditor()
      selectedText = editor.getSelectedText()
      range = editor.getSelectedBufferRange()

      if selectedText == ""
        # Nothing selected. See if they're over a var.
        if varName = @getClojureVarUnderCursor(editor)
          selectedText = varName
          range.end.column = Infinity

      options.inlineOptions =
        editor: editor
        range: range
      options.displayCode = selectedText
      options.doBlock = true
      @executeCodeInNs(selectedText, options)

  # A helper function for parsing some EDN data into JavaScript objects.
  parseEdn: (ednString)->
    edn_reader.parse(ednString)

  # Helper functions which takes an EDN string and pretty prints it. Returns the
  # string formatted data.
  prettyEdn: (ednString)->
    try
      edn_reader.pretty_print(ednString)
    catch error
      # Some responses from the REPL may be unparseable as in the case of var refs
      # like #'user/reset. We'll just return the original string in that case.
      return ednString

  # Parses the edn string and returns a displayable tree.  A tree is an array
  # whose first element is a string of the root of the tree. The rest of the
  # elements are branches off the root. Each branch is another tree. A leaf is
  # represented by a vector of one element.
  ednToDisplayTree: (ednString)->
    try
      edn_reader.to_display_tree(ednString)
    catch error
      # Some responses from the REPL may be unparseable as in the case of var refs
      # like #'user/reset. We'll just return the original string in that case.
      return [ednString]

  ednSavedValuesToDisplayTrees: (ednString)->
    try
      edn_reader.saved_values_to_display_trees(ednString)
    catch error
      console.log error
      return []

  # Converts a Javascript object to EDN. This is useful when you need to take a
  # JavaScript object and pass representation of it to Clojure running in the JVM.
  jsToEdn: (jsData)->
    edn_reader.js_to_edn(jsData)

  # Helper function for autoevaling results.
  executeRanges: (editor, ranges)->
    if range = ranges.shift()
      code = editor.getTextInBufferRange(range)

      # Selected code is executed in a do block so only a single value is returned.
      @executeCodeInNs code,
        inlineOptions:
          editor: editor
          range: range
        displayInRepl: false # autoEval only displays inline
        resultHandler: (result, options)=>
          @repl.inlineResultHandler(result, options)
          # Recurse back in again to execute the next range
          @executeRanges(editor, ranges)

  # Turns on auto evaluation of the current file.
  autoEvalCurrent: ->
    if !atom.config.get('proto-repl.showInlineResults')
      window.protoRepl.stderr("Auto Evaling is not supported unless inline results is enabled")
      return null

    if !@ink
      window.protoRepl.stderr("Install Atom Ink package to use auto evaling.")
      return null

    if editor = atom.workspace.getActiveTextEditor()
      if editor.protoReplAutoEvalDisposable
        window.protoRepl.stderr("Already auto evaling")
      else
        # Add a handler for when the editor stops changing
        editor.protoReplAutoEvalDisposable = editor.onDidStopChanging =>
          @ink?.Result.removeAll(editor)
          @executeRanges(editor, EditorUtils.getTopLevelRanges(editor))
        # Run it once the first time
        @executeRanges(editor, EditorUtils.getTopLevelRanges(editor))

  # Turns off autoevaling of the current file.
  stopAutoEvalCurrent: ->
    if editor = atom.workspace.getActiveTextEditor()
      if editor.protoReplAutoEvalDisposable
        editor.protoReplAutoEvalDisposable.dispose()
        editor.protoReplAutoEvalDisposable = null



  #############################################################################
  # Code helpers

  getClojureVarUnderCursor: (editor)->
    word = EditorUtils.getClojureVarUnderCursor(editor)
    if word == ""
      window.protoRepl.stderr("This command requires you to position the cursor on a Clojure var.")
      null
    else
      word

  prettyPrint: ->
    # Could make this work in self hosted repl by getting the last value and using
    # fipp to print it.
    @executeCode("(do (require 'clojure.pprint) (clojure.pprint/pp))")

  refreshNamespacesCommand:
    "(do
      (try
        (require 'user)
        (catch java.io.FileNotFoundException e
          (println (str \"No user namespace defined. Defaulting to clojure.tools.namespace.repl/refresh.\\n\"))))
      (try
        (require 'clojure.tools.namespace.repl)
        (catch java.io.FileNotFoundException e
          (println \"clojure.tools.namespace.repl not available. Add proto-repl in your project.clj as a dependency to allow refresh. See https://clojars.org/proto-repl\")))
      (let [user-reset 'user/reset
            ctnr-refresh 'clojure.tools.namespace.repl/refresh
            result (cond
                     (find-var user-reset)
                     ((resolve user-reset))

                     (find-var ctnr-refresh)
                     ((resolve ctnr-refresh))

                     :else
                      (println (str \"You can use your own refresh function, just define reset function in user namespace\\n\"
                                    \"See this https://github.com/clojure/tools.namespace#reloading-code-motivation for why you should use it\")))]
        (when (isa? (type result) Exception)
          (println (.getMessage result)))
        result))"

  refreshResultHandler: (callback, result)->
    # Value will contain an exception if it's not valid otherwise it will be nil
    # nil will also be returned if there is no clojure.tools.namespace available.
    # The callback will still be invoked in that case. That's important so that
    # run all tests will still work without it.
    if result.value
      window.protoRepl.info("Refresh complete")
      # Make sure the extension process is running after ever refresh.
      # If refreshing or laoding code had failed the extensions feature might not
      # have stopped itself.
      @extensionsFeature.startExtensionRequestProcessing()
      callback() if callback
    else if result.error
      window.protoRepl.stderr("Refresh Warning: " + result.error)

  # Refreshes any changed code in the project since the last refresh. Presumes
  # clojure.tools.namespace is a dependency and setup with standard user/reset
  # function. Will invoke the optional callback if refresh is successful.
  refreshNamespaces: (callback=null)->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Refreshing not supported in self hosted REPL.")
    else
      window.protoRepl.info("Refreshing code...\n")
      @executeCode @refreshNamespacesCommand,
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
      @executeCode "(do
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
        @executeCode("(do (println \"Loading File #{fileName}\") (load-file \"#{fileName}\"))")

  runTestsInNamespace: ->
    if editor = atom.workspace.getActiveTextEditor()
      if window.protoRepl.isSelfHosted()
        window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
      else
        code = "(clojure.test/run-tests)"
        if atom.config.get("proto-repl.refreshBeforeRunningTestFile")
          @refreshNamespaces =>
            @executeCodeInNs(code)
        else
          @executeCodeInNs(code)

  runTestUnderCursor: ->
    if editor = atom.workspace.getActiveTextEditor()
      if window.protoRepl.isSelfHosted()
        window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
      else
        if testName = @getClojureVarUnderCursor(editor)
          code = "(do (clojure.test/test-vars [#'#{testName}]) (println \"tested #{testName}\"))"
          if atom.config.get("proto-repl.refreshBeforeRunningSingleTest")
            @refreshNamespaces =>
              @executeCodeInNs(code)
          else
            @executeCodeInNs(code)

  runAllTests: ->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Running tests is not supported yet in self hosted REPL.")
    else
      @refreshNamespaces =>
        # Tests are only run if the refresh is successful.
        @executeCode("(def all-tests-future (future (time (clojure.test/run-all-tests))))")

  printVarDocumentation: ->
    if editor = atom.workspace.getActiveTextEditor()
      if varName = @getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          code = "(with-out-str (doc #{varName}))"
          parser = (value)-> value.replace(/^-+\n/, '')
        else
          code =
            "(do
               (require 'clojure.repl)
               (with-out-str (clojure.repl/doc #{varName})))"
          parser = (value)=> @parseEdn(value).replace(/^-+\n/, '')

        if @ink && atom.config.get('proto-repl.showInlineResults')
          range = editor.getSelectedBufferRange()
          range.end.column = Infinity
          inlineHandler = @repl.makeInlineHandler(editor, range, (value)=>
            [varName, {a: 1}, [parser(value)]])

        handled = false

        @executeCodeInNs code, displayInRepl: false, resultHandler: (value)=>
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
      if varName = @getClojureVarUnderCursor(editor)
        if window.protoRepl.isSelfHosted()
          # code = "(source #{varName})"
          window.protoRepl.stderr("Showing source code is not yet supported in self hosted REPL.")
        else
          code = "(do (require 'clojure.repl) (clojure.repl/source #{varName}))"
          @executeCodeInNs(code)

  # Lists all the vars in the selected namespace or namespace alias
  listNsVars: ->
    if editor = atom.workspace.getActiveTextEditor()
      if nsName = @getClojureVarUnderCursor(editor)
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
          @executeCodeInNs(code)

  # Lists all the vars with their documentation in the selected namespace or namespace alias
  listNsVarsWithDocs: ->
    if editor = atom.workspace.getActiveTextEditor()
      if nsName = @getClojureVarUnderCursor(editor)
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

          @executeCodeInNs(code)

  # Opens the file containing the currently selected var or namespace in the REPL. If the file is located
  # inside of a jar file it will decompress the jar file then open it. It will first check to see if a
  # jar file has already been decompressed once to avoid doing it multiple times for the same library.
  # Assumes that the Atom command line alias "atom" can be used to invoke Atom.
  openFileContainingVar: ->
    if window.protoRepl.isSelfHosted()
      window.protoRepl.stderr("Opening files containing vars is not yet supported in self hosted REPL.")
    else
      if editor = atom.workspace.getActiveTextEditor()
        if selected = @getClojureVarUnderCursor(editor)
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
          @executeCodeInNs text,
            displayInRepl: false
            resultHandler: (result)=>
              if result.value
                window.protoRepl.info("Opening #{result.value}")
                [file, line] = @parseEdn(result.value)
                file = file.replace(/%20/g, " ")
                atom.workspace.open(file, {initialLine: line-1, searchAllPanes: true})
              else
                window.protoRepl.stderr("Error trying to open: #{result.error}")

  remoteNreplFocusNext: ->
    if @connectionView?
      @connectionView.toggleFocus()
