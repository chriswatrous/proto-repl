(ns proto-repl.views.repl-view)

(defprotocol ReplView
  (on-did-close [this callback])
  (execute-entered-text [this])
  (clear [this])
  (info [this text])
  (stderr [this text])
  (stdout [this text])
  (doc [this text])
  (result [this text]))

(defn js-wrapper [view]
  #js{:info #(info view %)
      :stdout #(stdout view %)
      :stderr #(stderr view %)})
