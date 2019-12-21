(ns proto-repl.repl-connection.remote
  (:require [proto-repl.repl-connection :refer [ReplConnection]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private RemoteReplProcess (js/require "../lib/process/remote-repl-process"))

(defrecord RemoteReplConnection [old]
  ReplConnection
  (get-type [_] (.getType old))
  (get-current-ns [_] (.getCurrentNs old))
  (interrupt [_] (.interrupt old))
  (running? [_] (.running old))
  (send-command [_ command options callback] (.sendCommand old command (clj->js options) callback))
  (stop* [this session] (.stop old session)))

(defn connect-to-remote-repl [{:keys [view host port on-message on-start on-stop]}]
  (let [old (RemoteReplProcess. (rv/js-wrapper view))]
    (.start old #js{:host host
                    :port port
                    :messageHandler on-message
                    :startCallback on-start
                    :stopCallback on-stop})
    (map->RemoteReplConnection {:old old})))
