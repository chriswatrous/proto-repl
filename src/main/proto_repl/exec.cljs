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


(def commands {"proto-repl:execute-block" (partial #'execute-block {})
               "proto-repl:execute-top-block" (partial #'execute-block {:topLevel true})})


(comment
  (def editor (.getActiveTextEditor js/atom.workspace))
  (def range (.getCursorInBlockRange editor-utils editor (clj->js {:topLevel true})))
  ; (flash-highlight-range editor range)
  (def marker (.markBufferRange editor range))
  (.decorateMarker editor marker #js {:type "highlight" :class "block-execution"})
  (.destroy marker)

  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor #js {:type "highlight" :class "block-execution"})
    (js/setTimeout #(.destroy marker) 350))


  (let [editor (.getActiveTextEditor js/atom.workspace)
        range (.getCursorInBlockRange editor-utils editor (clj->js {:topLevel true}))]
        ; range (.getCursorInBlockRange editor-utils editor)]
    (println editor range)
    (flash-highlight-range editor range))

  (+ 1 1))
