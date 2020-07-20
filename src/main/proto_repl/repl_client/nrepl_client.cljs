(ns proto-repl.repl-client.nrepl-client
  (:refer-clojure :exclude [eval])
  (:require [clojure.string :as str]
            [clojure.walk :refer [prewalk]]
            [clojure.pprint :refer [pprint
                                    *print-miser-width*
                                    *print-right-margin*]]
            [clojure.core.async :as async
                                :refer [chan buffer put! close!]
                                :refer-macros [go]]
            [proto-repl.utils :refer [js-typeof
                                      safe-async-transduce
                                      safe-async-reduce
                                      update-if-present
                                      wrap-reducer-try-log]]
            [proto-repl.macros :refer-macros [go-try-log dochan! reducing-fn]]))


(def ^:private bencode (js/require "bencode"))
(def ^:private net (js/require "net"))


(defn- bencode-response->clj [data]
  "Convert bencode.decode response to cljs with keyword keys.
  bencode.decode returns Dict objects instead of plain objects so js->clj won't work."
  (cond
    (js/Array.isArray data) (mapv bencode-response->clj data)
    (= (js-typeof data) "object") (->> data
                                       js/Object.entries
                                       (map (fn [[k v]] [(keyword k)
                                                         (bencode-response->clj v)]))
                                       (into {}))
    :else data))


(defn- deep-remove-nil [data]
  (prewalk #(if (map? %)
              (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) m))
                         % %)
              %)
           data))


(defn- clj->bencode "Encode cljs data to bencode." [data]
  (-> data
      deep-remove-nil
      clj->js
      (bencode.encode "utf8")))


(defn- try-bencode-decode [buffer]
  (try
    (bencode.decode buffer "utf8")
    ; An exception will be thrown if the buffer is empty or doesn't
    ; contain a complete bencode message.
    (catch :default _)))


(defn- bencode-buffers->clj
  "A stateful transducer that converts bencoded Buffers to Clojure data."
  [rf]
  (let [buffer (volatile! (js/Buffer.from ""))
        rf1 (preserving-reduced rf)]
    (reducing-fn [rf1 result input]
      (vswap! buffer #(js/Buffer.concat #js [% input]))
      (reduce rf1 result
              (loop [messages []]
                (if-let [m (try-bencode-decode @buffer)]
                  (do (vswap! buffer #(.slice % (.-length (bencode.encode m))))

                      (recur (conj messages (bencode-response->clj m))))
                  messages))))))



(defn- status-to-keyword-set [message]
  (update-if-present message :status #(set (map keyword %))))


(defn- nrepl-connect "Open an nREPL connection." [{:keys [host port]}]
  (let [message-chan (chan (buffer 1) (comp bencode-buffers->clj
                                            (map status-to-keyword-set)))
        client-chan (chan)
        error-chan (chan)
        close-chans #(run! close! [message-chan error-chan client-chan])
        socket (net.createConnection #js {:host host :port port})]
     (doto socket
       (.on "close" close-chans)
       (.on "data" #(put! message-chan %))
       (.on "error" (fn [err]
                      (let [err (if (ex-message err) err (js/Error. err))]
                        (js/console.error "nREPL connection error" err)
                        (put! client-chan err)
                        (put! error-chan err))
                      (close-chans)))
       (.on "ready" (fn []
                      (put! client-chan {:host host
                                         :port port
                                         :error-chan error-chan
                                         :message-chan message-chan
                                         :socket socket})
                      (close! client-chan))))
    client-chan))


(defn- print-message [direction message]
  (binding [*print-miser-width* 120
            *print-right-margin* 120]
    (println direction (-> (with-out-str (pprint message))
                           (str/replace #"\n" (apply str "\n"
                                                     (repeat (inc (count direction)) " ")))
                           str/trim))))


(defn- wrap-request-response [{:keys [message-chan] :as client}]
  (let [pending-chans (atom {})]
    (go-try-log
      (loop []
        (when-let [message (<! message-chan)]
          (when js/window.protoRepl.logNreplMessages
            (print-message "<-" message))
          (if-let [c (@pending-chans (:id message))]
            (do (put! c message)
                (when (-> message :status :done)
                  (close! c)
                  (swap! pending-chans dissoc (:id message))))
            (js/console.error (str "no pending request for id \"" (:id message) "\"")))
          (recur)))
      (println "message-chan closed"))
    (-> client
        (dissoc :message-chan)
        (assoc :pending-chans pending-chans))))


(defn- send "Send an nREPL message." [{:keys [socket session-id]} message]
  (let [message (-> message
                    (assoc :session session-id)
                    (as-> % (if (:code %) (update % :code str) %)))]
    (when js/window.protoRepl.logNreplMessages
      (println "-------------------------")
      (print-message "->" message))
    (.write socket (clj->bencode message) "binary")))


(defn request
  "Send a request to an nREPL connection. Returns a channel that will
  convey any response messages associated with this request as they arrive."
  [client message]
  (let [id (str (random-uuid))
        c (chan)]
    (swap! (:pending-chans client) assoc id c)
    (send client (assoc message :id id))
    c))


(defn- wrap-create-session [client]
  (go-try-log
    (assoc client :session-id
           (<! (async/reduce (fn [result {:keys [new-session]}] (or new-session result))
                             nil
                             (request client {:op "clone"}))))))


(defn create-client [options]
  (go
    (try
      (let [client (<! (nrepl-connect options))]
        (if (ex-message client)
          client
          (-> client
              wrap-request-response
              wrap-create-session
              <!)))
      (catch :default err err))))


(defn close "Close an nREPL connection or client." [client]
  ; Close channels first to make sure an error doesn't get reported.
  (->> (concat (map client [:error-chan :message-chan])
               (some-> client :pending-chans deref vals))
       (remove nil?)
       (run! close!))
  (.destroy (:socket client)))


(defn- collect-eval-result [messages-chan]
  (safe-async-transduce
    cat
    (fn [result [k v]]
      (if (nil? v)
        result
        (case k
          :id (assoc result :id v)
          :ex (assoc result :ex v)
          :ns (assoc result :ns v)
          :root-ex (assoc result :root-ex v)
          :value (assoc result :value v)
          :session (assoc result :session v)
          :out (update result :out conj v)
          :err (update result :err conj v)
          :status (update result :status into v)
          result)))
    {:out []
     :err []
     :status #{}}
    messages-chan))


(defn eval [client options]
  (collect-eval-result (request client (-> options (assoc :op "eval") (update :code str)))))


(defn interrupt [{:keys [pending-chans session-id] :as client}]
  (doseq [id (keys @pending-chans)]
    (close! (request client {:op "interrupt" :interrupt-id id}))))


(defn get-value "Get the value from a channel of nREPL messages." [ch]
  (safe-async-reduce #(or %1 (:value %2)) nil ch))
