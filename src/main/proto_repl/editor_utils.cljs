(ns proto-repl.editor-utils
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["atom" :refer [Range]]
            [proto-repl.utils :refer [global-regex obj->map regex-or]]))


(def ^:private eu (js/require "../lib/editor-utils"))


(defn get-active-text-editor []
  (.getActiveTextEditor js/atom.workspace))


(defn- try-edn-read-string [s]
  (try (edn/read-string s)
       (catch :default _)))


(defn- format-scan-result [result]
  {:range (.-range result)
   :match-text (.-matchText result)})


(def ^:private brace-pattern #"[{}\[\]()]")
(def ^:private comment-pattern #"\(comment\s")
(def ^:private brace-or-comment-pattern (regex-or comment-pattern brace-pattern))


(defn- editor-scan [editor re]
  (let [out (volatile! (transient []))]
    (.scan editor (global-regex re) #(vswap! out conj! (format-scan-result %)))
    (persistent! @out)))


; TODO Maybe try to improve performance. This takes about 200ms in clojure/core.clj
(defn get-top-level-ranges [{:keys [editor look-in-comments]}]
  (->> (editor-scan editor (if look-in-comments brace-or-comment-pattern brace-pattern))
       (remove #(.isIgnorableBrace eu editor (-> % :range .-start)))
       (reduce
         (fn [[points level in-top-level-comment] {:keys [range match-text]}]
           (let [in-top-level-comment (or in-top-level-comment
                                          (and (= level 0)
                                               (re-matches comment-pattern match-text)))
                 at-level #(or (and (= level %) (not in-top-level-comment))
                               (and (= level (inc %)) in-top-level-comment))]
             (if (#{"(" "{" "["} (first match-text))
               [(if (at-level 0) (conj points (.-start range)) points)
                (inc level)
                in-top-level-comment]
               [(if (at-level 1) (conj points (.-end range)) points)
                (dec level)
                (and (not= level 1) in-top-level-comment)])))
         [[] 0 false])
       first
       (partition 2)
       (map #(Range. (first %) (second %)))))


(defn get-ns-from-declaration
  "Get the ns name (as a string) from the ns declaration in the current editor or
  the given editor."
  ([] (some-> (get-active-text-editor) get-ns-from-declaration))
  ([editor]
   (some->> (get-top-level-ranges {:editor editor})
            (map #(try-edn-read-string (.getTextInBufferRange editor %)))
            (filter #(and (list? %) (= (first %) 'ns)))
            first second str)))


(defn get-cursor-in-top-block-range
  "Returns the `Range` that corresponds to the top level form that contains the
  current cursor position. Doesn't work in Markdown blocks of code."
  [{:keys [editor look-in-comments]}]
  (let [pos (.getCursorBufferPosition editor)]
    (->> (get-top-level-ranges {:editor editor :look-in-comments look-in-comments})
         (filter #(.containsPoint % pos))
         first)))


(defn get-cursor-in-block-range
  "If the cursor is located in a Clojure block (in parentheses, brackets, or
  braces) or next to one returns the text of that block. Also works with
  Markdown blocks of code starting with  ```clojure  and ending with ```."
  [{:keys [editor top-level]}]
  (or (when top-level
        (get-cursor-in-top-block-range {:editor editor :look-in-comments true}))
      (.getCursorInClojureBlockRange eu editor)
      (.getCursorInMarkdownBlockRange eu editor)))
