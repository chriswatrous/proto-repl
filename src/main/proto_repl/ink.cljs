(ns proto-repl.ink)

(defonce ink (atom nil))
(declare Result)

(defn consume-ink [ink-]
  (reset! ink ink-)
  (def Result (.-Result ink-)))
