(ns proto-repl.editor-utils)

(def ^:private js-editor-utils (js/require "../lib/editor-utils"))


(defn get-active-text-editor [] (.getActiveTextEditor js/atom.workspace))


(def get-cursor-in-block-range)

(comment
  (def editor (get-active-text-editor))
  (-> editor
      js/Object.getPrototypeOf
      ; js/Object.getPrototypeOf
      js/Object.getOwnPropertyNames
      sort)

  (-> js/atom.workspace
      js/Object.getPrototypeOf
      js/Object.getOwnPropertyNames
      sort)

  (def pane (.getActivePane js/atom.workspace))

  (-> pane
      js/Object.getPrototypeOf
      js/Object.getOwnPropertyNames
      sort)

  (js/setTimeout #(.activate pane) 2000))
