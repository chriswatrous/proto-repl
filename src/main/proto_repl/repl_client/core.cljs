(ns proto-repl.repl-client.core)

(defprotocol ReplClient
  (get-type [this])
  (get-current-ns [this])
  (interrupt [this])
  (running? [this])
  (send-command [this command options callback] "protocol ReplClient
  Send a command to the repl.")
  (stop [this]))
