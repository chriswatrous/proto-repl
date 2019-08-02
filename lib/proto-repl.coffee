# Load the ClojureScript code
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

  toggleAutoScroll: ->
    cfg = atom.config
    cfg.set('proto-repl.autoScroll', !(cfg.get('proto-repl.autoScroll')))

  # Starts the REPL if it's not currently running.
  toggle: (projectPath=null)->
    if @repl == null
      @repl = new Repl(@extensionsFeature)
      @prepareRepl(@repl)
      @repl.startProcessIfNotRunning(projectPath)
    else
      # REPL already running
      @repl.startProcessIfNotRunning(projectPath)

  prepareRepl: (repl)->
    repl.consumeInk(@ink)
    repl.onDidClose =>
      @repl = null
      @emitter.emit('proto-repl:closed')
    repl.onDidStart =>
      @emitter.emit('proto-repl:connected')
      if atom.config.get("proto-repl.refreshOnReplStart")
        @refreshNamespaces()
    repl.onDidStop =>
      @extensionsFeature.stopExtensionRequestProcessing()
      @emitter.emit('proto-repl:stopped')

  onDidConnect: (callback) ->
    @emitter.on('proto-repl:connected', callback)

  onDidClose: (callback) ->
    @emitter.on('proto-repl:closed', callback)

  onDidStop: (callback) ->
    @emitter.on('proto-repl:stopped', callback)

  # Starts the REPL in the directory of the file in the current editor.
  toggleCurrentEditorDir: ->
    if editor = atom.workspace.getActiveTextEditor()
      if editorPath = editor.getPath()
        @toggle(path.dirname(editorPath))

  # Opens nRepl connection dialog
  remoteNReplConnection: ->
    confirmCallback = ({port, host})=>
      unless @repl
        @repl = new Repl(@extensionsFeature)
        @prepareRepl(@repl)
      @repl.startRemoteReplConnection({port, host})

    @connectionView ?= new NReplConnectionView(confirmCallback)
    @connectionView.show()

  selfHostedRepl: ->
    if @repl == null
      @repl = new Repl(@extensionsFeature)
      @prepareRepl(@repl)
      @repl.startSelfHostedConnection()
    else
      @repl.startSelfHostedConnection()

  # Returns true if the REPL is running
  running: ->
    @repl?.running()

  # Returns the type of the REPL that's currently running.
  # "SelfHosted", "Remote", Local"
  getReplType: ->
    @repl?.getType()

  isSelfHosted: ->
    @repl?.isSelfHosted()

  clearRepl: ->
    @repl?.clear()

  # Helpers for adding text to the REPL.
  info: (text)->
    @repl?.info(text)

  stderr: (text)->
    @repl?.stderr(text)

  stdout: (text)->
    @repl?.stdout(text)

  doc: (text)->
    @repl?.doc(text)

  # Interrupts the currently executing command.
  interrupt: ()->
    @repl?.interrupt()

  quitRepl: ->
    @repl?.exit()

  ##############################################################################
  ## Code Execution

  # Registers a code execution extension with the given name and callback function.
  #
  # Code execution extensions allow other Atom packages to extend Proto REPL
  # by taking output from the REPL and redirecting it for other uses like
  # visualization.
  # Code execution extensions are triggered when the result of code execution is
  # a vector with the first element is :proto-repl-code-execution-extension. The
  # second element in the vector should be the name of the extension to trigger.
  # The name will be used to locate the callback function. The third element in
  # the vector will be passed to the callback function.
  registerCodeExecutionExtension: (name, callback)->
    @extensionsFeature.registerCodeExecutionExtension(name, callback)

  # Executes the given code string in the REPL. See Repl.executeCode for supported
  # options.
  # executeCode: (code, options={})->
  #   @repl?.executeCode(code, options)

  # executeCodeInNs: (code, options={})->
  #   if editor = atom.workspace.getActiveTextEditor()
  #     ns = EditorUtils.findNsDeclaration(editor)
  #     if ns
  #       options.ns = ns
  #     @executeCode(code, options)

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

  # Executes the block of code near the cursor.
  # Valid options:
  # * resultHandler - a callback function to invoke with the value that was read.
  #   If this is passed in then the value will not be displayed in the REPL.
  # * topLevel - Boolean to indicate if the top level block should be indicated.
  #   If this is false the the closest block will be executed. Defaults to false.
  # executeBlock: (options={})->
  #   if editor = atom.workspace.getActiveTextEditor()
  #     if range = EditorUtils.getCursorInBlockRange(editor, options)
  #       text = editor.getTextInBufferRange(range).trim()
  #       options.displayCode = text
  #
  #       # Highlight the area that's being executed temporarily
  #       marker = editor.markBufferRange(range)
  #       decoration = editor.decorateMarker(marker,
  #           {type: 'highlight', class: "block-execution"})
  #       # Remove the highlight after a short period of time
  #       setTimeout(=>
  #         marker.destroy()
  #       , 350)
  #
  #       options.inlineOptions =
  #         editor: editor
  #         range: range
  #
  #       @executeCodeInNs(text, options)

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
      @stderr("Auto Evaling is not supported unless inline results is enabled")
      return null

    if !@ink
      @stderr("Install Atom Ink package to use auto evaling.")
      return null

    if editor = atom.workspace.getActiveTextEditor()
      if editor.protoReplAutoEvalDisposable
        @stderr("Already auto evaling")
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
      @stderr("This command requires you to position the cursor on a Clojure var.")
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
      @info("Refresh complete")
      # Make sure the extension process is running after ever refresh.
      # If refreshing or laoding code had failed the extensions feature might not
      # have stopped itself.
      @extensionsFeature.startExtensionRequestProcessing()
      callback() if callback
    else if result.error
      @stderr("Refresh Warning: " + result.error)

  # Refreshes any changed code in the project since the last refresh. Presumes
  # clojure.tools.namespace is a dependency and setup with standard user/reset
  # function. Will invoke the optional callback if refresh is successful.
  refreshNamespaces: (callback=null)->
    if @isSelfHosted()
      @stderr("Refreshing not supported in self hosted REPL.")
    else
      @info("Refreshing code...\n")
      @executeCode @refreshNamespacesCommand,
        displayInRepl: false,
        resultHandler: (result)=>
          @refreshResultHandler(callback, result)

  # Refreshes all of the code in the project whether it has changed or not.
  # Presumes clojure.tools.namespace is a dependency and setup with standard
  # user/reset function. Will invoke the optional callback if refresh is
  # successful.
  superRefreshNamespaces: (callback=null)->
    if @isSelfHosted()
      @stderr("Refreshing not supported in self hosted REPL.")
    else
      @info("Clearing all and then refreshing code...\n")
      @executeCode "(do
                      (when (find-ns 'clojure.tools.namespace.repl)
                        (eval '(clojure.tools.namespace.repl/clear)))
                      #{@refreshNamespacesCommand})",
        displayInRepl: false,
        resultHandler: (result)=> @refreshResultHandler(callback, result)

  loadCurrentFile: ->
    if editor = atom.workspace.getActiveTextEditor()
      if @isSelfHosted()
        @stderr("Loading files is not supported yet in self hosted REPL.")
      else
        # Escape file name
        fileName = editor.getPath().replace(/\\/g,"\\\\")
        @executeCode("(do (println \"Loading File #{fileName}\") (load-file \"#{fileName}\"))")

  runTestsInNamespace: ->
    if editor = atom.workspace.getActiveTextEditor()
      if @isSelfHosted()
        @stderr("Running tests is not supported yet in self hosted REPL.")
      else
        code = "(clojure.test/run-tests)"
        if atom.config.get("proto-repl.refreshBeforeRunningTestFile")
          @refreshNamespaces =>
            @executeCodeInNs(code)
        else
          @executeCodeInNs(code)

  runTestUnderCursor: ->
    if editor = atom.workspace.getActiveTextEditor()
      if @isSelfHosted()
        @stderr("Running tests is not supported yet in self hosted REPL.")
      else
        if testName = @getClojureVarUnderCursor(editor)
          code = "(do (clojure.test/test-vars [#'#{testName}]) (println \"tested #{testName}\"))"
          if atom.config.get("proto-repl.refreshBeforeRunningSingleTest")
            @refreshNamespaces =>
              @executeCodeInNs(code)
          else
            @executeCodeInNs(code)

  runAllTests: ->
    if @isSelfHosted()
      @stderr("Running tests is not supported yet in self hosted REPL.")
    else
      @refreshNamespaces =>
        # Tests are only run if the refresh is successful.
        @executeCode("(def all-tests-future (future (time (clojure.test/run-all-tests))))")

  printVarDocumentation: ->
    if editor = atom.workspace.getActiveTextEditor()
      if varName = @getClojureVarUnderCursor(editor)
        if @isSelfHosted()
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
              @doc(msg)
            else
              @stderr("No doc found for #{varName}")
          else if value.error
            @stderr(value.error)
          else
            msg = 'Did not get value or error.'
            console.error(msg, value)
            throw new Error(msg)

  printVarCode: ->
    if editor = atom.workspace.getActiveTextEditor()
      if varName = @getClojureVarUnderCursor(editor)
        if @isSelfHosted()
          # code = "(source #{varName})"
          @stderr("Showing source code is not yet supported in self hosted REPL.")
        else
          code = "(do (require 'clojure.repl) (clojure.repl/source #{varName}))"
          @executeCodeInNs(code)

  # Lists all the vars in the selected namespace or namespace alias
  listNsVars: ->
    if editor = atom.workspace.getActiveTextEditor()
      if nsName = @getClojureVarUnderCursor(editor)
        if @isSelfHosted()
          # code = "(dir #{nsName})"
          @stderr("Listing namespace functions is not yet supported in self hosted REPL.")
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
        if @isSelfHosted()
          # code = "(dir #{nsName})"
          @stderr("Listing namespace functions is not yet supported in self hosted REPL.")
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
    if @isSelfHosted()
      @stderr("Opening files containing vars is not yet supported in self hosted REPL.")
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
                @info("Opening #{result.value}")
                [file, line] = @parseEdn(result.value)
                file = file.replace(/%20/g, " ")
                atom.workspace.open(file, {initialLine: line-1, searchAllPanes: true})
              else
                @stderr("Error trying to open: #{result.error}")

  remoteNreplFocusNext: ->
    if @connectionView?
      @connectionView.toggleFocus()
