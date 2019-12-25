(ns proto-repl.repl-connection.process
  (:require [proto-repl.repl-connection :refer [ReplConnection]]
            [proto-repl.views.repl-view :as rv]))

(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))
(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))

(defrecord ProcessReplConnection [old conn view]
  ReplConnection
  (get-type [_] "Process")
  (get-current-ns [_] (.getCurrentNs old))
  (interrupt [_]
    (.interrupt conn)
    (rv/info view "Interrupting"))
  (running? [_] (.running old))
  (send-command [_ command options callback] (.sendCommand old command (clj->js options) callback))
  (stop [_]
    (doto old
      (-> .-conn .close)
      (some-> .-process (.send #js{:event "kill"}))
      (aset "process" nil))))

(defn start-repl-process [{:keys [view project-path on-message on-start on-stop]}]
  (let [old (LocalReplProcess.)
        conn (NReplConnection.)]
    (aset old "replView" (rv/js-wrapper view))
    (aset old "conn" conn)
    (.start old project-path #js{:messageHandler on-message
                                 :startCallback on-start
                                 :stopCallback on-stop})
    (map->ProcessReplConnection {:old old :conn conn :view view})))
