(ns proto-repl.repl-connection.remote
  (:require [proto-repl.repl-connection :refer [ReplConnection]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))

(defrecord RemoteReplConnection [conn on-stop view]
  ReplConnection
  (get-type [_] "Remote")

  (get-current-ns [_] (.getCurrentNs conn))

  (interrupt [_]
    (.interrupt conn)
    (rv/info view "Interrupting"))

  (running? [_] (.connected conn))

  (send-command [_ command options callback]
    (.sendCommand conn command (clj->js options) callback))

  (stop [_]
    (when on-stop (on-stop))
    (.close conn)))

(defn connect-to-remote-repl [{:keys [view host port on-message on-start on-stop]}]
  (map->RemoteReplConnection
    {:view view
     :on-stop on-stop
     :conn (doto (NReplConnection.)
             (.start #js{:host host
                         :port port
                         :messageHandler on-message
                         :startCallback on-start}))}))
