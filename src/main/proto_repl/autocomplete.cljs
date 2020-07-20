(ns proto-repl.autocomplete
  "Implements an Autocomplete Provider for Clojure that uses compliment
  https://github.com/alexander-yakushev/compliment"
  (:require [clojure.edn :as edn]
            ["atom" :refer [Range Point]]
            [proto-repl.editor-utils :refer [get-ns-from-declaration get-top-level-ranges]]
            [proto-repl.utils :refer [get-config safe-async-reduce wrap-lookup]]
            [proto-repl.commands :refer [nrepl-request running?]]
            [proto-repl.macros :refer-macros [go-try-log go-promise template-fill]]
            [proto-repl.repl-client.nrepl-client :as nrepl]))


(defn- run-completion-request [code]
  (go-try-log
    (some-> (nrepl-request {:op "eval" :code code})
            nrepl/get-value <! edn/read-string)))


(defn- completion->suggestion
  "Converts a completion result into a suggestion for Autocomplete"
  [prefix {:keys [candidate docs type]}]
  #js {:text candidate
       :type type
       :description docs
       :replacementPrefix prefix})


(defn- buffer-position-context
  "Returns the top level code around the given buffer position
  with the cursor position replaced with __prefix__ as described by
  https://github.com/alexander-yakushev/compliment/wiki/Context"
  [editor position prefix]
  (if-let [range (->> (get-top-level-ranges {:editor editor})
                      (filter #(.containsPoint % position))
                      first)]
    (str (.getTextInBufferRange editor (Range. (.-start range)
                                               (Point. (.-row position)
                                                       (- (.-column position) (count prefix)))))
         "__prefix__"
         (.getTextInBufferRange editor (Range. position (.-end range))))
    "nil"))


(defn- get-prefix
  "We ignore the prefix used by autocomplete because it doesn't respect real clojure
  identifiers. Based on
  https://github.com/atom/autocomplete-plus/wiki/Provider-API#generating-a-new-prefix"
  [editor buffer-position]
  (let [; Whatever your prefix regex might be])
        regex #"[A-Za-z0-9_\-></.?!*:]+$"
        ; Get the text for the line up to the triggered buffer position
        line (.getTextInRange editor #js [#js [buffer-position.row 0] buffer-position])]
    ; Match the regex to the line, and return the match
    (or (re-find regex line) "")))


(defn- completions-code [editor buffer-position prefix]
  (let [current-ns (or (get-ns-from-declaration editor) "nil")
        context-str (pr-str (buffer-position-context editor buffer-position prefix))]
    (template-fill [prefix current-ns context-str]
      (require 'compliment.core)
      (->> (compliment.core/completions "prefix" {:tag-candidates true
                                                  :ns 'current-ns
                                                  :context context-str})
           (take 50)
           (mapv #(assoc % :docs (compliment.core/documentation (:candidate %) 'current-ns)))))))


(defn- get-suggestions [arg]
  (when running?
    (let [{:keys [editor bufferPosition]} (wrap-lookup arg)]
      (when-let [prefix (not-empty (get-prefix editor bufferPosition))]
        (go-promise
          (some->> (completions-code editor bufferPosition prefix)
                   run-completion-request
                   <!
                   (map #(completion->suggestion prefix %))
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
