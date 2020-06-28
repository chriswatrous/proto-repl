(ns proto-repl.editor-utils)


(defn get-active-text-editor [] (.getActiveTextEditor js/atom.workspace))
