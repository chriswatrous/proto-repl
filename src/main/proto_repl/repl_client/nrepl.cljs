(ns proto-repl.repl-client.nrepl
  (:require [clojure.string :as str]
            [proto-repl.repl-client.core :refer [ReplClient] :as rc]
            [proto-repl.views.repl-view :as rv]
            [proto-repl.repl-client.nrepl-client]))

(def ^:private lodash (js/require "lodash"))
(def ^:private nrepl (js/require "jg-nrepl-client"))
(def ^:private ClojureVersion (js/require "../lib/process/clojure-version"))
(def ^:private EditorUtils (js/require "../lib/editor-utils"))

(def ^:private default-ns "user")

(defn- notify-error [err]
  (js/atom.notifications.addError (str "proto-repl") #js {:detail err :dismissable true}))

(defn- contains-namespace-not-found-error? [messages]
  (->> messages
       (mapcat #(.-status %))
       (some #(= % "namespace-not-found"))))

(defn- may-contain-reader-conditional
  "Returns true if the code might have a reader conditional in it
  Avoids unnecesary evaling for regular code."
  [code]
  (str/includes? code "#?"))

(defn- options-to-sessions [{:keys [conn session cmd-session sessions-by-name]}
                            options callback]
  (cond
    (:allSessions options)
    (callback (concat [@session @cmd-session]
                      (vals @sessions-by-name)))
    (:session options)
    (if-let [s (@sessions-by-name (:session options))]
      (callback [s])
      (.clone @conn
        (fn [err messages]
          (some-> err notify-error)
          (let [s (lodash.get messages #js [0 "new-session"])]
            (swap! sessions-by-name assoc (:session options) s)
            (callback [s])))))
    (= (:displayInRepl options) false)
    (callback [@cmd-session])
    :else
    (callback [@session])))

(defn- wrap-code-in-read-eval
  "Wraps the given code in an eval and a read-string. This is required for
  handling reader conditionals. http://clojure.org/guides/reader_conditionals"
  [{:keys [clojure-version-obj]} code]
  (if (and (some-> @clojure-version-obj (.isReaderConditionalSupported))
           (may-contain-reader-conditional code))
    (let [escaped-str (EditorUtils.escapeClojureCodeInString code)]
      (println "wrapped")
      (str "(eval (read-string {:read-cond :allow} " escaped-str "))"))
    code))

(defn- send-command* [{:keys [conn current-ns] :as this} command options callback]
  (when (rc/running? this)
    (options-to-sessions this options
      (fn [sessions]
        (doseq [s sessions]
          ; Wrap code in read eval to handle invalid code and reader conditionals
          (let [wrapped-code (wrap-code-in-read-eval this command)
                ns (or (:ns options) @current-ns)
                eval-options (merge
                               {:op "eval" :code wrapped-code :ns ns :session s}
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
                                       (assoc options :retrying true :ns @current-ns)
                                       callback))
                    (do (doseq [msg messages]
                          (if-let [value (.-value msg)]
                            (callback #js {:value value})
                            (when-let [err (.-err msg)]
                              (callback #js {:error err}))))))
                  (catch :default e
                    (js/console.error e)
                    (notify-error e)))))))))))

(defrecord NReplClient [host port conn session cmd-session sessions-by-name on-stop current-ns
                        clojure-version-obj]
  ReplClient
  (get-type [_] "Remote")
  (get-current-ns [_] @current-ns)

  (interrupt [this]
    (when (rc/running? this)
      (doto @conn
        (.interrupt @session (fn [_ _]))
        (.interrupt @cmd-session (fn [_ _])))))

  (running? [_] (some? @conn))

  (send-command [this command options callback] (send-command* this command options callback))

  (stop [this]
    (when on-stop (on-stop))
    (when (rc/running? this)
      (doto @conn
        (.close @session (fn [_ _]))
        (.close @cmd-session (fn [_ _])))
      (reset! session nil)
      (reset! cmd-session nil)
      (reset! sessions-by-name {})
      (reset! conn nil))))

(defn- determine-clojure-version [{:keys [conn session clojure-version-obj]} callback]
  (.eval @conn "*clojure-version*" "user" @session
    (fn [err messages]
      (some-> err notify-error)
      (reset! clojure-version-obj (-> messages first .-value js/window.protoRepl.parseEdn
                                      ClojureVersion.))
      (when-not (.isSupportedVersion @clojure-version-obj)
        (js/atom.notifications.addWarning
          (str "WARNING: This version of Clojure is not supported by Proto REPL. You may "
               "experience issues.")
          #js {:dismissable true}))
      (callback))))

(defn- start-message-handling
  "Log any output from the nRepl connection messages"
  [{:keys [conn current-ns session cmd-session]} on-message]
  (.on (.-messageStream @conn) "messageSequence"
    (fn [id messages]
      (when-not (contains-namespace-not-found-error? messages)
        (doseq [msg messages]
          ; Set the current ns, but only if the message is in response
          ; to something sent by the user through the REPL)))))
          (when (and (.-ns msg) (= (.-session msg) @session))
            (reset! current-ns (.-ns msg)))
          (when (or (= (.-session msg) @session)
                    (and (= (.-session msg) @cmd-session)
                         (.-out msg)))
            (on-message msg)))))))

(defn connect-to-nrepl [{:keys [host port on-message on-start on-stop]}]
  (let [host (or (not-empty host) "localhost")
        conn (atom (nrepl.connect #js {:port port :host host :verbose false}))
        session (atom nil)
        cmd-session (atom nil)
        this (map->NReplClient {:host host
                                :port port
                                :conn conn
                                :session session
                                :cmd-session cmd-session
                                :clojure-version-obj (atom nil)
                                :current-ns (atom default-ns)
                                :sessions-by-name (atom {})
                                :on-stop on-stop})]
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
            (reset! session (lodash.get messages #js[0 "new-session"]))
            (determine-clojure-version this
              ; Handle multiple callbacks for this which can happen during REPL
              ; startup with cider-nrepl middleware for some reason.
              (memoize #(start-message-handling this on-message)))
            (.clone @conn
              (fn [err messages]
                (some-> err notify-error)
                (reset! cmd-session (lodash.get messages #js[0 "new-session"]))
                (on-start)))))))
    this))


(comment
  (def c (nrepl.connect #js {:port 2345 :host "localhost" :verbose false}))

  (js/console.log nrepl)
  (js/console.log c)

  (sort (js/Object.keys c))
  (sort (js/Object.getOwnPropertyNames c))

  (.clone c (fn [err messages & more]
              (js/console.log "err" err)
              (js/console.log "messages" messages)))

  (def m (js->clj+ js/temp1))

  ; .clone
  ; .send
  ; .interrupt
  ; .close
  ; .eval
  ; .-messageStream

  (def uuid (js/require "uuid"))
  (uuid.v4)
  (js/Buffer.from "")
  (.log js/console "qwer"))

(defn js->clj+ [data]
  (-> data
      js/JSON.stringify
      js/JSON.parse
      (js->clj :keywordize-keys true)))
