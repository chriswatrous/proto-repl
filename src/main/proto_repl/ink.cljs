(ns proto-repl.ink)

(declare ink)
(declare Result)

(defn init [ink-]
  (def ink ink-)
  (def Result (.-Result ink-)))
