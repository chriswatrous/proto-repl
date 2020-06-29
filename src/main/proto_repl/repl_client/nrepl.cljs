(ns proto-repl.repl-client.nrepl
  (:require [proto-repl.repl-client.core :refer [ReplClient] :as rc]
            [proto-repl.views.repl-view :as rv]))

(def ^:private lodash (js/require "lodash"))
(def ^:private nrepl (js/require "jg-nrepl-client"))
(def ^:private ClojureVersion (js/require "../lib/process/clojure-version"))
(def ^:private EditorUtils (js/require "../lib/editor-utils"))
(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))

(def ^:private default-ns "user")

(defrecord NReplClient [old on-stop]
  ReplClient
  (get-type [_] "Remote")
  (get-current-ns [_] (.getCurrentNs old))

  (interrupt [this]
    (when (rc/running? this)
      (let [conn (.-conn old)]
        (.interrupt conn (.-session old) (fn [_ _]))
        (.interrupt conn (.-cmdSession old) (fn [_ _])))))

  (running? [_] (some? (.-conn old)))

  (send-command [_ command options callback]
    (.sendCommand old command (clj->js options) callback))

  (stop [_]
    (when on-stop (on-stop))
    (.close old)))

(defn- notify-error [err]
  (js/atom.notifications.addError (str "proto-repl: connection error")
                                  #js {:detail err :dismissable true}))

(defn connect-to-nrepl [{:keys [host port on-message on-start on-stop]}]
  (let [old (NReplConnection.)
        host (or (not-empty host) "localhost")
        this (map->NReplClient {:on-stop on-stop
                                :old old})
        conn (.connect nrepl #js {:port port :host host :verbose false})]
    (aset old "conn" conn)
    (aset old "messageHandlingStarted" false)
    (aset old "currentNs" default-ns)
    (.on conn "error"
      (fn [err]
        (when (rc/running? this) (notify-error err))
        (aset old "conn" nil)))
    (.once conn "connect"
      (fn []
        (.on conn "finish" #(aset old "conn" nil))
        (.clone conn
          (fn [err messages]
            (some-> err notify-error)
            (aset old "session" (lodash.get messages #js[0 "new-session"]))
            (.determineClojureVersion old
              ; Handle multiple callbacks for this which can happen during REPL
              ; startup with cider-nrepl middleware for some reason.
              (memoize #(.startMessageHandling old on-message)))
            (.clone conn
              (fn [err messages]
                (some-> err notify-error)
                (aset old "cmdSession" (lodash.get messages #js[0 "new-session"]))
                (on-start)))))))
    this))
