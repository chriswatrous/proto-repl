(ns proto-repl.repl-connection.remote
  (:require [proto-repl.repl-connection :refer [ReplConnection]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private RemoteReplProcess (js/require "../lib/process/remote-repl-process"))
(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))

(defrecord RemoteReplConnection [old conn view]
  ReplConnection
  (get-type [_] "Remote")
  (get-current-ns [_] (.getCurrentNs old))
  (interrupt [_] (.interrupt old))
  (running? [_] (.running old))
  (send-command [_ command options callback] (.sendCommand old command (clj->js options) callback))
  (stop* [this session] (.stop old session)))

(defn connect-to-remote-repl [{:keys [view host port on-message on-start on-stop]}]
  (let [conn (NReplConnection.)
        old (RemoteReplProcess.)]
    (js/Object.assign old #js{:replView (rv/js-wrapper view)
                              :conn conn
                              :stopCallback on-stop})
    (.start conn #js{:host host
                     :port port
                     :messageHandler on-message
                     :startCallback on-start})
    (map->RemoteReplConnection {:old old
                                :view view
                                :conn conn
                                :on-stop on-stop})))
