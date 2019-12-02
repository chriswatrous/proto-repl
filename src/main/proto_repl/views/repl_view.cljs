(ns proto-repl.views.repl-view)

(defprotocol ReplView
  (on-did-close [this callback]))
