(ns proto-repl.autocomplete
  (:require [clojure.edn :as edn]
            [proto-repl.utils :refer [get-config safe-async-reduce]]
            [proto-repl.commands :refer [nrepl-request running?]]
            [proto-repl.macros :refer-macros [go-promise]]
            [cljs.pprint :refer [pprint]]))

(def ^:private cp (js/require "../lib/completion-provider"))

(defn nrepl-get-value "Get the value from a channel of nREPL messages." [c]
  (safe-async-reduce (fn [result {:keys [value]}] (or result value)) nil c))

(defn- run-completion-request [code]
  (some->> (nrepl-request {:op "eval" :code code})
           (safe-async-reduce
             (fn [result {:keys [value]}] (or result value))
             nil)))

(defn- get-suggestions [arg]
  (when running?
    (let [editor (.-editor arg)
          buffer-position (.-bufferPosition arg)
          scope-descriptor (.-scopeDescriptor arg)]
      (when-let [prefix (not-empty (cp.getPrefix editor buffer-position))]
        (go-promise
          (some->> (cp.completionsCode editor buffer-position prefix)
                   run-completion-request
                   <!
                   edn/read-string
                   (map #(cp.completionToSuggestion prefix (clj->js %)))
                   (apply array)))))))


(defn provide-autocomplete "Called by autocomplete-plus to return our Clojure provider" []
  (when (get-config :enable-completions)
    #js {:scopeSelector ".source.clojure"
         :textEditorSelectors "atom-text-editor"
         :disableForScopeSelector ".source.clojure .comment, .source.clojure .string"
         :inclusionPriority 100
         :excludeLowerPriority false
         :getTextEditorSelector (constantly "atom-text-editor")
         :getSuggestions #(get-suggestions %)}))
