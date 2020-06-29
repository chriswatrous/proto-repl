(ns proto-repl.repl-client.nrepl
  (:require [clojure.string :as str]
            [proto-repl.repl-client.core :refer [ReplClient] :as rc]
            [proto-repl.views.repl-view :as rv]))

(def ^:private lodash (js/require "lodash"))
(def ^:private nrepl (js/require "jg-nrepl-client"))
(def ^:private ClojureVersion (js/require "../lib/process/clojure-version"))
(def ^:private EditorUtils (js/require "../lib/editor-utils"))

(def ^:private default-ns "user")

(defn- notify-error [err]
  (js/atom.notifications.addError (str "proto-repl") #js{:detail err :dismissable true}))

(defn- contains-namespace-not-found-error? [messages]
  (->> messages
       (mapcat #(.-status %))
       (some #(= % "namespace-not-found"))))

(defn- may-contain-reader-conditional
  "Returns true if the code might have a reader conditional in it
  Avoids unnecesary eval'ing for regular code."
  [code]
  (str/includes? code "#?"))

(defn- options-to-sessions [{:keys [conn sessions-by-name old]} options callback]
  (cond
    (:allSessions options)
    (callback (concat [(.-session old) (.-cmdSession old)]
                      (vals @sessions-by-name)))
    (:session options)
    (if-let [s (@sessions-by-name (:session options))]
      (callback [s])
      (do (.clone @conn
            (fn [err messages]
              (some-> err notify-error)
              (let [s (lodash.get messages #js[0 "new-session"])]
                (swap! sessions-by-name assoc (:session options) s)
                (callback [s]))))))
    (= (:displayInRepl options) false)
    (callback [(.-cmdSession old)])
    :else
    (callback [(.-session old)])))

(defn- wrap-code-in-read-eval
  "Wraps the given code in an eval and a read-string. This is required for
  handling reader conditionals. http://clojure.org/guides/reader_conditionals"
  [{:keys [old]} code]
  (if (and (some-> old .-clojureVersion (.isReaderConditionalSupported))
           (may-contain-reader-conditional code))
    (let [escaped-str (EditorUtils.escapeClojureCodeInString code)]
      (println "wrapped")
      (str "(eval (read-string {:read-cond :allow} " escaped-str "))"))
    code))

(defn- send-command* [{:keys [conn old] :as this} command options callback]
  (when (rc/running? this)
    (options-to-sessions this options
      (fn [sessions]
        (doseq [session sessions]
          ; Wrap code in read eval to handle invalid code and reader conditionals
          (let [wrapped-code (wrap-code-in-read-eval this command)
                ns (or (:ns options) (.-currentNs old))
                eval-options (merge
                               {:op "eval" :code wrapped-code :ns ns :session session}
                               (when-let [range (-> options :inlineOptions :range)]
                                 {:line (-> range .-start .-row inc)
                                  :column (-> range .-start .-column inc)})
                               (when-let [editor (-> options :inlineOptions :editor)]
                                 {:file (.getPath editor)}))
                cb-count (atom 0)]
            (.send @conn (clj->js eval-options)
              (fn [err messages]
                (swap! cb-count inc)
                (some-> err notify-error)
                (try
                  ; If the namespace hasn't been defined this will fail. We redefine the Namespace
                  ; and retry.
                  (if (contains-namespace-not-found-error? messages)
                    ; FIXME This callback is sometimes called twice for one call to .send so we
                    ; need to guard the retry.
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

(defrecord NReplClient [host port conn sessions-by-name on-stop old]
  ReplClient
  (get-type [_] "Remote")
  (get-current-ns [_] (.-currentNs old))

  (interrupt [this]
    (when (rc/running? this)
      (doto @conn
        (.interrupt (.-session old) (fn [_ _]))
        (.interrupt (.-cmdSession old) (fn [_ _])))))

  (running? [_] (some? @conn))

  (send-command [this command options callback] (send-command* this command options callback))

  (stop [this]
    (when on-stop (on-stop))
    (when (rc/running? this)
      (doto @conn
        (.close (.-session old))
        (.close (.-cmdSession old)))
      (reset! sessions-by-name {})
      (reset! conn nil))))

(defn- determine-clojure-version [{:keys [conn old]} callback]
  (.eval @conn "*clojure-version*" "user" (.-session old)
    (fn [err messages]
      (some-> err notify-error)
      (aset old "clojureVersion" (-> messages first .-value js/window.protoRepl.parseEdn
                                     ClojureVersion.))
      (when-not (.isSupportedVersion (.-clojureVersion old))
        (js/atom.notifications.addWarning
          (str "WARNING: This version of Clojure is not supported by Proto REPL. You may "
               "experience issues.")
          #js{:dismissable true}))
      (callback))))

(defn- start-message-handling
  "Log any output from the nRepl connection messages"
  [{:keys [conn old]} on-message]
  (.on (.-messageStream @conn) "messageSequence"
    (fn [id messages]
      (when-not (contains-namespace-not-found-error? messages)
        (doseq [msg messages]
          ; Set the current ns, but only if the message is in response
          ; to something sent by the user through the REPL)))))
          (if (and (.-ns msg) (= (.-session msg) (.-session old)))
            (aset old "currentNs" (.-ns msg)))
          (when (or (= (.-session msg) (.-session old))
                    (and (= (.-session msg) (.-cmdSession old))
                         (.-out msg)))
            (on-message msg)))))))

(defn connect-to-nrepl [{:keys [host port on-message on-start on-stop]}]
  (let [old #js{}
        host (or (not-empty host) "localhost")
        conn (atom (.connect nrepl #js {:port port :host host :verbose false}))
        this (map->NReplClient
               {:host host
                :port port
                :conn conn
                :sessions-by-name (atom {})
                :on-stop on-stop
                :old old})]
    (aset old "currentNs" default-ns)
    (.on @conn "error"
      (fn [err]
        (when (rc/running? this) (notify-error err))
        (reset! conn nil)))
    (.once @conn "connect"
      (fn []
        (.on @conn "finish" #(reset! conn nil))
        (.clone @conn
          (fn [err messages]
            (some-> err notify-error)
            (aset old "session" (lodash.get messages #js[0 "new-session"]))
            (determine-clojure-version this
              ; Handle multiple callbacks for this which can happen during REPL
              ; startup with cider-nrepl middleware for some reason.
              (memoize #(start-message-handling this on-message)))
            (.clone @conn
              (fn [err messages]
                (some-> err notify-error)
                (aset old "cmdSession" (lodash.get messages #js[0 "new-session"]))
                (on-start)))))))
    this))

(comment
  (->> proto-repl.commands/repl deref :connection deref :old))


(defn copy-plain [obj]
  (js/Object.assign #js{} obj))
