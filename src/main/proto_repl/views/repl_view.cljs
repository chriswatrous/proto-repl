(ns proto-repl.views.repl-view)

(defprotocol ReplView
  (on-did-close [this callback])
  (on-eval [this callback])
  (display-executed-code [this code])
  (get-and-clear-entered-text [this])
  (clear [this])
  (info [this text])
  (stderr [this text])
  (stdout [this text])
  (doc [this text])
  (result [this text]))
