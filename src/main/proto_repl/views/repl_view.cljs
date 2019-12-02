(ns proto-repl.views.repl-view)

(defprotocol ReplView
  (on-did-close [this callback])
  (clear [this])
  (info [this text])
  (stderr [this text])
  (stdout [this text])
  (doc [this text]))
