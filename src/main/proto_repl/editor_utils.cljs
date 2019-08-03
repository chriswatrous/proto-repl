(ns proto-repl.editor-utils
  (:require [proto-repl.plugin :refer [stderr]]))

(def ^:private js-editor-utils (js/require "../lib/editor-utils"))


(defn get-active-text-editor [] (.getActiveTextEditor js/atom.workspace))


(defn get-var-under-cursor [editor]
  (let [word (.getWordUnderCursor editor #js {:wordRegex #"[a-zA-Z0-9\-.$!?\/><*=_]+"})]
    (if (seq word)
      word
      (do
        (stderr "This command requires you to position the cursor on a Clojure var.")
        nil))))
