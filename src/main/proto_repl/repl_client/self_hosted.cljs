(ns proto-repl.repl-client.self-hosted
  (:require [proto-repl.repl-client.core :refer [ReplClient]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private SelfHostedProcess (js/require "../lib/process/self-hosted-process"))

(defrecord SelfHostedReplConnection [old]
  ReplClient
  (get-type [_] (.getType old))
  (get-current-ns [_] (.getCurrentNs old))
  (interrupt [_] (.interrupt old))
  (running? [_] (.running old))
  (send-command [_ command options callback] (.sendCommand old command (clj->js options) callback))
  (stop [_] (.stop old)))

(defn start-self-hosted-repl [{:keys [view on-message on-start on-stop]}]
  (let [old (SelfHostedProcess. (rv/js-wrapper view))]
    (.start old #js{:messageHandler on-message
                    :startCallback on-start
                    :stopCallback on-stop})
    (map->SelfHostedReplConnection {:old old})))
