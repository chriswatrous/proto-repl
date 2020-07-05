(ns proto-repl.core
  (:require ["atom" :refer [CompositeDisposable Range Point]]
            [proto-repl.commands :as c :refer [repl]]
            [proto-repl.utils :as u :refer [get-bind]]
            [proto-repl.repl :as r]
            [proto-repl.integration.core]
            [proto-repl.ink :as ink]))

(def ^:private edn-reader (js/require "../lib/proto_repl/edn_reader"))
(def ^:private CompletionProvider (js/require "../lib/completion-provider"))
(def ^:private ExtensionsFeature (js/require "../lib/features/extensions-feature"))

(defonce subscriptions (CompositeDisposable.))
(defonce toolbar (atom nil))


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
    (reset! toolbar tb)))


(defn- provide-autocomplete []
  "Called by autocomplete-plus to return our Clojure provider"
  (if (js/atom.config.get "proto-repl.enableCompletions") CompletionProvider []))


(defn- activate []
  (.add subscriptions
        (.add
          js/atom.commands
          "atom-workspace"
          (clj->js
            {"proto-repl:autoeval-file" #(c/autoeval-file)
             "proto-repl:clear-repl" #(c/clear-repl)
             "proto-repl:execute-block" #(c/execute-block {:top-level false})
             "proto-repl:execute-selected-text" #(c/execute-selected-text)
             "proto-repl:execute-text-entered-in-repl" #(c/execute-text-entered-in-repl)
             "proto-repl:execute-top-block" #(c/execute-block {:top-level true})
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
             "proto-repl:stop-autoeval-file" #(c/stop-autoeval-file)
             "proto-repl:super-refresh-namespaces" #(c/super-refresh-namespaces)
             "proto-repl:toggle-auto-scroll" #(c/toggle-auto-scroll)}))))


(defn- deactivate []
  (.dispose subscriptions)
  (some-> @toolbar .removeItems)
  (some-> @repl r/exit)
  (reset! repl nil))


(def exports
  (clj->js {:activate activate
            :config config
            :consumeToolbar consume-toolbar
            :consumeInk ink/init
            :deactivate deactivate
            :provide provide-autocomplete}))


(set!
  js/window.protoRepl
  #js {:onDidConnect #(.on c/emitter "proto-repl:connected" %)
       :onDidClose #(.on c/emitter "proto-repl:closed" %)
       :onDidStop #(.on c/emitter "proto-repl:stopped" %)
       :running #(r/running? @repl)
       :registerCodeExecutionExtension c/register-code-execution-extension
       :getClojureVarUnderCursor c/get-var-under-cursor
       :executeCode #(c/execute-code %1 (or (js->clj %2 :keywordize-keys true) {}))
       :executeCodeInNs #(c/execute-code-in-ns %1 (or (js->clj %2 :keywordize-keys true) {}))
       :isSelfHosted (fn [] false)

       ; Utility functions
       :parseEdn (get-bind edn-reader :parse)
       :prettyEdn u/pretty-edn
       :ednToDisplayTree u/edn->display-tree
       :jsToEdn u/js->edn
       :ednSavedValuesToDisplayTrees u/edn-saved-values->display-trees

       ; Helpers for adding text to the REPL.
       :info c/info
       :stderr c/stderr
       :stdout c/stdout
       :doc c/doc})


(let [notification (js/atom.notifications.addInfo "proto-repl loaded" #js {:dismissable true})]
  (js/console.log "proto-repl loaded")
  (js/setTimeout #(.dismiss notification) 1000))
