(ns proto-repl.editor-utils)

(def ^:private js-editor-utils (js/require "../lib/editor-utils"))


(defn get-active-text-editor [] (.getActiveTextEditor js/atom.workspace))


(def get-cursor-in-block-range)
