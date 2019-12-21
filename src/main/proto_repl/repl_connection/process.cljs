(ns proto-repl.repl-connection.process
  (:require [proto-repl.repl-connection :refer [ReplConnection]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))

(defrecord ProcessReplConnection [old]
  ReplConnection
  (get-type [_] (.getType old))
  (get-current-ns [_] (.getCurrentNs old))
  (interrupt [_] (.interrupt old))
  (running? [_] (.running old))
  (send-command [_ command options callback] (.sendCommand old command (clj->js options) callback))
  (stop [_] (.stop old)))

(defn start-repl-process [{:keys [view project-path on-message on-start on-stop]}]
  (let [old (LocalReplProcess. (rv/js-wrapper view))]
    (.start old project-path #js{:messageHandler on-message
                                 :startCallback on-start
                                 :stopCallback on-stop})
    (map->ProcessReplConnection {:old old})))
