(ns proto-repl.core
  (:require ["atom" :refer [CompositeDisposable Range Point Emitter]]
            [proto-repl.commands :as c]
            [proto-repl.editor-utils :as eu]
            [proto-repl.plugin :as p :refer [state-merge! state-get]]
            [proto-repl.utils :as u :refer [get-bind]]))


(def ^:private lodash (js/require "lodash"))
(def ^:private proto-repl (js/require "../lib/proto-repl"))
(def ^:private edn-reader (js/require "../lib/proto_repl/edn_reader"))
(def ^:private CompletionProvider (js/require "../lib/completion-provider"))
(def ^:private SaveRecallFeature (js/require "../lib/features/save-recall-feature"))
(def ^:private ExtensionsFeature (js/require "../lib/features/extensions-feature"))


(def ^:private config
  {:refreshBeforeRunningSingleTest
   {:description (str "Configures whether the REPL should automatically refresh code before "
                      "running a single selected test.")
    :default true
    :type "boolean"}
   :bootArgs
   {:description "The arguments to be passed to boot. For advanced users only."
    :default "--no-colors dev repl --server wait"
    :type "string"}
   :autoScroll
   {:description "Sets whether or not the REPL scrolls when new content is written."
    :default true
    :type "boolean"}
   :clojurePath
   {:description "The path to the clojure executable."
    :default "clj"
    :type "string"}
   :enableCompletions
   {:description (str "Configures whether autocompletion of Clojure forms should be supported. "
                      "Changing this requires a restart of Atom.")
    :default true
    :type "boolean"}
   :preferredRepl
   {:description (str "Sets the order of preference for REPLs if your project has multiple build "
                      "files.")
    :default ["lein" "boot" "gradle" "clojure"]
    :type "array"
    :items {:enum ["lein" "boot" "gradle" "clojure"]
            :type "string"}}
   :leinArgs
   {:description "The arguments to be passed to leiningen. For advanced users only."
    :default "repl :headless"
    :type "string"}
   :openReplInRightPane
   {:description (str "Configure whether the REPL should open in a pane to the right or the "
                      "current pane.")
    :default true
    :type "boolean"}
   :refreshBeforeRunningTestFile
   {:description (str "Configures whether the REPL should automatically refresh code before "
                      "running all the tests in a file.")
    :default true
    :type "boolean"}
   :autoPrettyPrint
   {:description "Configures whether the REPL automatically pretty prints values."
    :default false
    :type "boolean"}
   :historySize
   {:description "The number of elements to keep in the history"
    :default 50
    :type "number"}
   :bootPath
   {:description "The path to the boot executable."
    :default "boot"
    :type "string"}
   :showInlineResults
   {:description "Shows inline results of code execution. Install Atom Ink package to use this."
    :default true
    :type "boolean"}
   :refreshOnReplStart
   {:description "Configures whether the REPL should automatically refresh code when it starts."
    :default true
    :type "boolean"}
   :gradleArgs
   {:description "The arguments to be passed to gradle. For advanced users only."
    :default ":clojureRepl --console=plain --quiet"
    :type "string"}
   :displayHelpText
   {:description "Enables the display of help text when the REPL starts."
    :default true
    :type "boolean"}
   :inkConsole
   {:description (str "Configure whether to use the Atom Ink console for the REPL output. If set "
                      "to false a regular text editor is used for output.")
    :default true
    :type "boolean"}
   :leinPath
   {:description "The path to the lein executable."
    :default "lein"
    :type "string"}
   :useClojureSyntax
   {:description (str "Sets whether or not the REPL should use Clojure syntax for highlighting. "
                      "Disable this if having performance issues with REPL display.")
    :default true
    :type "boolean"}
   :displayExecutedCodeInRepl
   {:description "Sets whether code sent to the REPL is displayed."
    :default true
    :type "boolean"}})


(defn- consume-toolbar [make-toolbar]
  (let [tb (make-toolbar "proto-repl")]
    (doto tb
      (.addButton #js {:icon "android-refresh"
                       :iconset "ion"
                       :callback "proto-repl:refresh-namespaces"
                       :tooltip "Refresh Namespaces"})
      (.addButton #js {:icon "android-sync"
                       :iconset "ion"
                       :callback "proto-repl:super-refresh-namespaces"
                       :tooltip "Clear and Refresh Namespaces"})
      (.addButton #js {:icon "speedometer"
                       :iconset "ion"
                       :callback "proto-repl:run-all-tests"
                       :tooltip "Run All Tests"})
      (.addSpacer)
      (.addButton #js {:icon "paypal"
                       :iconset "fa"
                       :callback "proto-repl:pretty-print"
                       :tooltip "Pretty Print"})
      (.addSpacer)
      (.addButton #js {:icon "code-download"
                       :iconset "ion"
                       :callback "proto-repl:toggle-auto-scroll"
                       :tooltip "Toggle Auto Scroll"})
      (.addButton #js {:icon "trash-a"
                       :iconset "ion"
                       :callback "proto-repl:clear-repl"
                       :tooltip "Clear REPL"})
      (.addSpacer)
      (.addButton #js {:icon "power"
                       :iconset "ion"
                       :callback "proto-repl:exit-repl"
                       :tooltip "Quit REPL"}))
    (state-merge! {:toolbar tb})))


(defn- provide-autocomplete []
  "Called by autocomplete-plus to return our Clojure provider"
  (if (js/atom.config.get "proto-repl.enableCompletions") CompletionProvider []))


(defn- activate []
  (state-merge! {:subscriptions (CompositeDisposable.)
                 :emitter (Emitter.)
                 :saveRecallFeature (SaveRecallFeature. js/window.proto-repl)
                 :extensionsFeature (ExtensionsFeature. js/window.proto-repl)})
  (.add (state-get :subscriptions)
        (.add
          js/atom.commands
          "atom-workspace"
          (clj->js
            {"proto-repl:autoeval-file" #(c/autoeval-file)
             "proto-repl:clear-repl" #(c/clear-repl)
             "proto-repl:execute-block" #(c/execute-block {})
             "proto-repl:execute-selected-text" #(c/execute-selected-text)
             "proto-repl:execute-text-entered-in-repl" #(c/execute-text-entered-in-repl)
             "proto-repl:execute-top-block" #(c/execute-block {:topLevel true})
             "proto-repl:exit-repl" #(c/exit-repl)
             "proto-repl:interrupt" #(c/interrupt)
             "proto-repl:list-ns-vars-with-docs" (state-get :listNsVarsWithDocs)
             "proto-repl:list-ns-vars" (state-get :listNsVars)
             "proto-repl:load-current-file" (state-get :loadCurrentFile)
             "proto-repl:open-file-containing-var" (state-get :openFileContainingVar)
             "proto-repl:pretty-print" #(c/pretty-print)
             "proto-repl:print-var-code" (state-get :printVarCode)
             "proto-repl:print-var-documentation" (state-get :printVarDocumentation)
             "proto-repl:refresh-namespaces" #(c/refresh-namespaces)
             "proto-repl:remote-nrepl-connection" #(c/remote-nrepl-connection)
             "proto-repl:remote-nrepl-focus-next" (state-get :remoteNreplFocusNext)
             "proto-repl:run-all-tests" (state-get :runAllTests)
             "proto-repl:run-test-under-cursor" (state-get :runTestUnderCursor)
             "proto-repl:run-tests-in-namespace" (state-get :runTestsInNamespace)
             "proto-repl:start-self-hosted-repl" #(c/start-self-hosted-repl)
             "proto-repl:stop-autoeval-file" #(c/stop-autoeval-file)
             "proto-repl:super-refresh-namespaces" (state-get :superRefreshNamespaces)
             "proto-repl:toggle-auto-scroll" #(c/toggle-auto-scroll)
             "proto-repl:toggle-current-project-clj" #(c/toggle-current-editor-dir)
             "proto-repl:toggle" #(c/toggle)}))))


(defn- deactivate []
  (.dispose (state-get :subscriptions))
  (.deactivate (state-get :saveRecallFeature))
  (state-merge! {:saveRecallFeature nil})
  (some-> (state-get :toolbar) .removeItems)
  (when (state-get :repl)
    ((state-get :quitRepl))
    (state-merge! {:repl nil})))


(defn- consume-ink [ink]
  (state-merge! {:ink ink})
  (some-> (state-get :repl) (.consumeInk ink))
  (state-merge! {:loading (ink.Loading.)}))


(def exports
  (clj->js {:activate activate
            :config config
            :consumeToolbar consume-toolbar
            :consumeInk consume-ink
            :deactivate deactivate
            :provide provide-autocomplete}))


(set! js/window.protoRepl
      (-> proto-repl
          lodash.clone
          lodash.bindAll
          (js/Object.assign
            #js {:onDidConnect #(.on (state-get :emitter) "proto-repl:connected" %)
                 :onDidClose #(.on (state-get :emitter) "proto-repl:closed" %)
                 :onDidStop #(.on (state-get :emitter) "proto-repl:stopped" %)
                 :running #(.running (state-get :repl))
                 :getReplType #(.getType (state-get :repl))
                 :isSelfHosted p/self-hosted?
                 :registerCodeExecutionExtension p/register-code-execution-extension
                 :getClojureVarUnderCursor eu/get-var-under-cursor
                 :executeCode p/execute-code
                 :executeCodeInNs p/execute-code-in-ns

                 ; Utility functions
                 :parseEdn (get-bind edn-reader :parse)
                 :prettyEdn u/pretty-edn
                 :ednToDisplayTree u/edn->display-tree
                 :jsToEdn u/js->edn
                 :ednSavedValuesToDisplayTrees u/edn-saved-values->display-trees

                 ; Helpers for adding text to the REPL.)
                 :info p/info
                 :stderr p/stderr
                 :stdout p/stdout
                 :doc p/doc})))


(let [notification (js/atom.notifications.addInfo "proto-repl loaded"
                                                  #js {:dismissable true})]
  (js/console.log "proto-repl loaded")
  (js/setTimeout #(.dismiss notification) 1000))

(comment
  js/process.pid
  nil)
