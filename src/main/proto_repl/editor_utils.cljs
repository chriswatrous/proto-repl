(ns proto-repl.editor-utils
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["atom" :refer [Range]]
            [proto-repl.utils :refer [comp+ global-regex obj->map regex-or]]
            [proto-repl.macros :refer-macros [reducing-fn]]))


(def ^:private eu (js/require "../lib/editor-utils"))


(defn get-active-text-editor []
  (.getActiveTextEditor js/atom.workspace))


(defn- format-scan-result [result]
  {:range (.-range result)
   :match-text (.-matchText result)})


(defn- editor-scan
  "Scan an Editor for regex matches using its .scan method, returning a vector
  of results. Supplying a transducer allows you to stop the scanning early to
  improve performance."
  ([editor re] (editor-scan editor re nil))
  ([editor re xform]
   (let [out (volatile! (transient []))
         rf ((comp+ (map format-scan-result) xform) conj!)]
     (.scan editor (global-regex re)
            (fn [scan-result]
              (vswap! out #(let [next (rf % scan-result)]
                             (if (reduced? next)
                               (do (.stop scan-result) @next)
                               next)))))
     (persistent! (rf @out)))))


(def ^:private brace-pattern #"[{}\[\]()]")
(def ^:private comment-pattern #"\(comment\s")
(def ^:private brace-or-comment-pattern (regex-or comment-pattern brace-pattern))


(defn- real-brace-results->top-level-ranges
  "A stateful transducer that converts real brace match results to top level ranges."
  [look-in-comments]
  (fn [rf]
    (let [start (volatile! nil)
          level (volatile! 0)
          in-top-level-comment (volatile! false)
          at-adjusted-level? #(or (and (= @level %) (not @in-top-level-comment))
                                  (and (= @level (inc %)) @in-top-level-comment))]
      (reducing-fn [rf result {:keys [range match-text]}]
        (if (#{"(" "{" "["} (first match-text))
          (do (vswap! level inc)
              (when (and (= @level 1) (re-matches comment-pattern match-text))
                (vreset! in-top-level-comment true))
              (when (at-adjusted-level? 1)
                (vreset! start (.-start range)))
              result)
          (do (vswap! level dec)
              (let [new-result (if (at-adjusted-level? 0)
                                 (rf result (Range. @start (.-end range)))
                                 result)]
                (when (= @level 0)
                  (vreset! in-top-level-comment false))
                new-result)))))))


(defn get-top-level-ranges
  "Get the top level ranges in the given Editor. Supplying a transducer allows
  you to stop the scanning early to improve performance."
  [{:keys [editor look-in-comments xform]}]
  (editor-scan
    editor
    (if look-in-comments brace-or-comment-pattern brace-pattern)
    (comp+ (remove #(.isIgnorableBrace eu editor (-> % :range .-start)))
           (real-brace-results->top-level-ranges look-in-comments)
           xform)))


(defn first-top-level-range
  "Get the first result from passing the top level ranges through xform."
  [{:keys [editor look-in-comments xform]}]
  (first
    (get-top-level-ranges
      {:editor editor
       :look-in-comments look-in-comments
       :xform (comp+ xform (take 1))})))


(defn- try-edn-read-string [s]
  (try (edn/read-string s)
       (catch :default _)))


(defn get-ns-from-declaration
  "Get the ns name (as a string) from the ns declaration in the current editor or
  the given editor."
  ([] (some-> (get-active-text-editor) get-ns-from-declaration))
  ([editor]
   (some-> (first-top-level-range
             {:editor editor
              :xform (comp (map #(try-edn-read-string (.getTextInBufferRange editor %)))
                           (filter #(and (list? %) (= (first %) 'ns))))})
           second str)))


(defn get-cursor-in-top-block-range
  "Returns the `Range` that corresponds to the top level form that contains the
  current cursor position. Doesn't work in Markdown blocks of code."
  [{:keys [editor look-in-comments]}]
  (let [pos (.getCursorBufferPosition editor)]
    (first-top-level-range
      {:editor editor
       :look-in-comments look-in-comments
       :xform (filter #(.containsPoint % pos))})))


(defn get-cursor-in-block-range
  "If the cursor is located in a Clojure block (in parentheses, brackets, or
  braces) or next to one returns the text of that block. Also works with
  Markdown blocks of code starting with  ```clojure  and ending with ```."
  [{:keys [editor top-level]}]
  (or (when top-level
        (get-cursor-in-top-block-range {:editor editor :look-in-comments true}))
      (.getCursorInClojureBlockRange eu editor)
      (.getCursorInMarkdownBlockRange eu editor)))
