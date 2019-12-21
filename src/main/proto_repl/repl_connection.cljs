(ns proto-repl.repl-connection)

(defprotocol ReplConnection
  (get-type [this])
  (get-current-ns [this])
  (interrupt [this])
  (running? [this])
  (send-command [this command options callback])
  (stop [this]))
