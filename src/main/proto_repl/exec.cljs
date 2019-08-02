(ns proto-repl.exec
  (:require [proto-repl.command-registry :refer [register-command]]
            [proto-repl.plugin :refer [execute-code-in-ns]]))

(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defn flash-highlight-range [editor range]
  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor marker #js {:type "highlight" :class "block-execution"})
    (js/setTimeout #(.destroy marker) 350)))


(defn- execute-block [options]
  (when-let [editor (.getActiveTextEditor js/atom.workspace)]
    (when-let [range (.getCursorInBlockRange editor-utils editor (clj->js options))]
      (flash-highlight-range editor range)
      (let [text (-> editor (.getTextInBufferRange range) .trim)
            options (assoc options :displayCode text
                                   :inlineOptions {:editor editor
                                                   :range range})]
        (execute-code-in-ns text options)))))
