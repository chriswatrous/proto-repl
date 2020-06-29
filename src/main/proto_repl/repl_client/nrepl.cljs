(ns proto-repl.repl-client.nrepl
  (:require [proto-repl.repl-client.core :refer [ReplClient] :as rc]
            [proto-repl.views.repl-view :as rv]))

(def ^:private lodash (js/require "lodash"))
(def ^:private nrepl (js/require "jg-nrepl-client"))
(def ^:private ClojureVersion (js/require "../lib/process/clojure-version"))
(def ^:private EditorUtils (js/require "../lib/editor-utils"))
(def ^:private NReplConnection (js/require "../lib/process/nrepl-connection"))

(def ^:private default-ns "user")

(defn- notify-error [err]
  (js/atom.notifications.addError (str "proto-repl") #js {:detail err :dismissable true}))

(defn- options-to-sessions [{:keys [old]} options callback]
  (cond
    (:allSessions options)
    (callback (concat [(.-session old) (.-cmdSession old)]
                      (-> old .-sessionsByName js/Object.values)))
    (:session options)
    (if-let [s (-> old .-sessionsByName (aget (:session options)))]
      (callback [s])
      (do (.clone (.-conn old)
            (fn [err messages]
              (some-> err notify-error)
              (let [s (lodash.get messages #js[0 "new-session"])]
                (aset (.-sessionsByName old) (:session options) s)
                (callback [s]))))))
    (= (:displayInRepl options) false)
    (callback [(.-cmdSession old)])
    :else
    (callback [(.-session old)])))

(defn- send-command* [{:keys [old] :as this} command options callback]
  (when (rc/running? this)
    (options-to-sessions this options
      (fn [sessions]
        (doseq [session sessions]
          ; Wrap code in read eval to handle invalid code and reader conditionals
          (let [wrapped-code (.wrapCodeInReadEval old command)
                ns (or (:ns options) (.-currentNs old))
                eval-options (merge
                               {:op "eval" :code wrapped-code :ns ns :session session}
                               (when-let [range (-> options :inlineOptions :range)]
                                 {:line (-> range .-start .-row inc)
                                  :column (-> range .-start .-column inc)})
                               (when-let [editor (-> options :inlineOptions :editor)]
                                 {:file (.getPath editor)}))
                cb-count (atom 0)]
            (.send (.-conn old) (clj->js eval-options)
              ; FIXME This callback is sometimes called twice for one call to .send so we need to
              ; guard the retry.
              (fn [err messages]
                (swap! cb-count inc)
                (some-> err notify-error)
                (try
                  ; If the namespace hasn't been defined this will fail. We redefine the Namespace
                  ; and retry.
                  (if (.namespaceNotFound old messages)
                    (when (and (= @cb-count 1) (not (:retrying options)))
                      (rc/send-command this
                                       command
                                       (assoc options :retrying true :ns (.-currentNs old))
                                       callback))
                    (do (doseq [msg messages]
                          (if-let [value (.-value msg)]
                            (callback #js{:value value})
                            (when-let [err (.-err msg)]
                              (callback #js{:error err}))))))
                  (catch :default e
                    (js/console.error e)
                    (notify-error e)))))))))))

(defrecord NReplClient [host port old on-stop]
  ReplClient
  (get-type [_] "Remote")
  (get-current-ns [_] (.getCurrentNs old))

  (interrupt [this]
    (when (rc/running? this)
      (let [conn (.-conn old)]
        (.interrupt conn (.-session old) (fn [_ _]))
        (.interrupt conn (.-cmdSession old) (fn [_ _])))))

  (running? [_] (some? (.-conn old)))

  (send-command [this command options callback]
    (send-command* this command options callback))

  (stop [this]
    (when on-stop (on-stop))
    (when (rc/running? this)
      (-> old .-conn (.close (.-session old)))
      (-> old .-conn (.close (.-cmdSession old)))
      (aset old "sessionsByName" #js{})
      (aset old "conn" nil))))

(defn connect-to-nrepl [{:keys [host port on-message on-start on-stop]}]
  (let [old (NReplConnection.)
        host (or (not-empty host) "localhost")
        this (map->NReplClient {:host host :port port :on-stop on-stop :old old})
        conn (.connect nrepl #js {:port port :host host :verbose false})]
    (aset old "conn" conn)
    (aset old "messageHandlingStarted" false)
    (aset old "sessionsByName" #js{})
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

(comment
  (do (:old @(:connection @proto-repl.commands/repl)))

  (-> proto-repl.commands/repl deref :connection deref :old .-conn)
  (-> proto-repl.commands/repl deref :connection deref :old .-sessionsByName)

  (->> proto-repl.commands/repl deref :connection deref :old)
  (->> proto-repl.commands/repl))


(defn copy-plain [obj]
  (js/Object.assign #js{} obj))


(defn obj-data [obj]
  (let [obj (js/Object.assign #js{} obj)]
    (doseq [key (js/Object.keys obj)]
      (let [value (aget obj key)]
        (when (lodash.isObject value)
          (aset obj key (obj-data value)))))
    obj))
