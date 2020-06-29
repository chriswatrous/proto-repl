(ns proto-repl.repl-client.process
  (:require [proto-repl.repl-client.core :refer [ReplClient] :as rc]
            [proto-repl.views.repl-view :as rv]))

(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))
(def ^:private LocalReplProcess (js/require "../lib/process/local-repl-process"))

(defn start [this {:keys [project-path on-message on-start on-stop]}]
  (when (rc/running? this)
    ; Default project path to the current directory. This can still set the
    ; projectPath to null if no file is opened in atom.
    (let [project-path (or project-path (-> js/atom.project .getPaths first))]
      (-> this :old
          (.start project-path #js{:messageHandler on-message
                                   :startCallback on-start
                                   :stopCallback on-stop})))))

(defrecord ProcessReplConnection [old conn]
  ReplClient
  (get-type [_] "Process")
  (get-current-ns [_] (.getCurrentNs conn))
  (interrupt [_] (.interrupt conn))
  (running? [_] (.connected conn))

  (send-command [_ command options callback]
    (.sendCommand conn command (clj->js options) callback))

  (stop [_]
    (doto old
      (-> .-conn .close)
      (some-> .-process (.send #js{:event "kill"}))
      (aset "process" nil))))

(defn start-repl-process [{:keys [view] :as options}]
  (let [conn (NReplConnection.)
        old (doto (LocalReplProcess.)
              (aset "replView" (rv/js-wrapper view))
              (aset "conn" conn))
        this (map->ProcessReplConnection {:old old :conn conn :view view})]
    (start this options)
    this))
