(ns proto-repl.core
  (:require ["atom" :refer [CompositeDisposable Range Point Emitter]]
            [proto-repl.commands :as c]
            [proto-repl.editor-utils :as eu]
            [proto-repl.master :as p :refer [state]]
            [proto-repl.utils :as u :refer [get-bind]]
            [proto-repl.integration.core]))

(def ^:private lodash (js/require "lodash"))
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
    (swap! state assoc :toolbar tb)))


(defn- provide-autocomplete []
  "Called by autocomplete-plus to return our Clojure provider"
  (if (js/atom.config.get "proto-repl.enableCompletions") CompletionProvider []))


(defn- activate []
  (swap! state assoc
         :subscriptions (CompositeDisposable.)
         :emitter (Emitter.)
         :saveRecallFeature (SaveRecallFeature.)
         :extensionsFeature (ExtensionsFeature.))
  (.add (:subscriptions @state)
        (.add
          js/atom.commands
          "atom-workspace"
          (clj->js
            {"proto-repl:autoeval-file" #(c/autoeval-file)
             "proto-repl:clear-repl" #(c/clear-repl)
             "proto-repl:execute-block" #(c/execute-block)
             "proto-repl:execute-selected-text" #(c/execute-selected-text)
             "proto-repl:execute-text-entered-in-repl" #(c/execute-text-entered-in-repl)
             "proto-repl:execute-top-block" #(c/execute-block {:topLevel true})
             "proto-repl:exit-repl" #(c/exit-repl)
             "proto-repl:interrupt" #(c/interrupt)
             "proto-repl:list-ns-vars-with-docs" #(c/list-ns-vars-with-docs)
             "proto-repl:list-ns-vars" #(c/list-ns-vars)
             "proto-repl:load-current-file" #(c/load-current-file)
             "proto-repl:open-file-containing-var" #(c/open-file-containing-var)
             "proto-repl:pretty-print" #(c/pretty-print)
             "proto-repl:print-var-code" #(c/print-var-code)
             "proto-repl:print-var-documentation" #(c/print-var-documentation)
             "proto-repl:refresh-namespaces" #(c/refresh-namespaces)
             "proto-repl:remote-nrepl-connection" #(c/remote-nrepl-connection)
             "proto-repl:remote-nrepl-focus-next" #(c/remote-nrepl-focus-next)
             "proto-repl:run-all-tests" #(c/run-all-tests)
             "proto-repl:run-test-under-cursor" #(c/run-test-under-cursor)
             "proto-repl:run-tests-in-namespace" #(c/run-tests-in-namespace)
             "proto-repl:start-self-hosted-repl" #(c/start-self-hosted-repl)
             "proto-repl:stop-autoeval-file" #(c/stop-autoeval-file)
             "proto-repl:super-refresh-namespaces" #(c/super-refresh-namespaces)
             "proto-repl:toggle-auto-scroll" #(c/toggle-auto-scroll)
             "proto-repl:toggle-current-project-clj" #(c/toggle-current-editor-dir)
             "proto-repl:toggle" #(c/toggle)}))))


(defn- deactivate []
  (-> @state :subscription .dispose)
  (-> @state :saveRecallFeature .deactivate)
  (swap! state assoc :saveRecallFeature nil)
  (some-> @state :toolbar .removeItems)
  (when (:repl @state)
    ((:quitRepl @state))
    (swap! state assoc :repl nil)))


(defn- consume-ink [ink]
  (swap! state assoc :ink ink)
  (some-> @state :repl (.consumeInk ink))
  (swap! state assoc :loading (ink.Loading.)))


(def exports
  (clj->js {:activate activate
            :config config
            :consumeToolbar consume-toolbar
            :consumeInk consume-ink
            :deactivate deactivate
            :provide provide-autocomplete}))


(set!
  js/window.protoRepl
  #js {:onDidConnect #(-> @state :emitter (.on "proto-repl:connected" %))
       :onDidClose #(-> @state :emitter (.on "proto-repl:closed" %))
       :onDidStop #(-> @state :emitter (.on "proto-repl:stopped" %))
       :running #(-> @state :repl .running)
       :getReplType #(-> @state :repl .getType)
       :isSelfHosted p/self-hosted?
       :registerCodeExecutionExtension p/register-code-execution-extension
       :getClojureVarUnderCursor eu/get-var-under-cursor
       :executeCode #(p/execute-code %1 (or (js->clj %2 :keywordize-keys true) {}))
       :executeCodeInNs #(p/execute-code-in-ns %1 (or (js->clj %2 :keywordize-keys true) {}))

       ; Utility functions
       :parseEdn (get-bind edn-reader :parse)
       :prettyEdn u/pretty-edn
       :ednToDisplayTree u/edn->display-tree
       :jsToEdn u/js->edn
       :ednSavedValuesToDisplayTrees u/edn-saved-values->display-trees

       ; Helpers for adding text to the REPL.
       :info p/info
       :stderr p/stderr
       :stdout p/stdout
       :doc p/doc})


(let [notification (js/atom.notifications.addInfo "proto-repl loaded" #js {:dismissable true})]
  (js/console.log "proto-repl loaded")
  (js/setTimeout #(.dismiss notification) 1000))
