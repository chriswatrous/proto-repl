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
