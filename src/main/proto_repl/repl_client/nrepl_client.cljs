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
                                      wrap-reducer-try-log]]
            [proto-repl.macros :refer-macros [go-try-log dochan!]]))

(def ^:private bencode (js/require "bencode"))
(def ^:private net (js/require "net"))

(declare client)

(comment
  (nrepl-close client)

  (go-try-log
    ; (def client (<! (make-nrepl-client {:host "localhost" :port 2345})))
    (def client (<! (make-nrepl-client {:host "localhost" :port 1111}))))

  (go-try-log
    (dochan! [m (nrepl-request client {:op "eval" :code (str '(println "qwer")
                                                             '(println "qwer"))})]))

  (go-try-log
    (dochan! [m (nrepl-request client {:op "eval" :code (str '(+ 1 1))})]))

  (go-try-log
    (dochan! [m (nrepl-request client {:op "eval"
                                       :code (str '(println "Qwer"))})]))

  (def session "747ab495-e85a-4c5f-baac-a85dc82baa71")

  (send client {:op "ls-sessions"})
  (send client {:op "clone"})
  (send client {:op "close" :session "183e4504-68b6-40cc-9d3e-bc37fb6c3da8"})
  (send client {:op "describe"})
  (send client {:op "interrupt" :session "" :interrupt-id "12345"})
  (send client {:op "eval"
                :id "12345"
                :code (str '(dotimes [_ 5]
                              (Thread/sleep 1000)
                              (println "asdf")))}))

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
    (fn ([] (rf1))
        ([result] (rf1 result))
        ([result input]
         (vswap! buffer #(js/Buffer.concat #js [% input]))
         (reduce rf1 result
                 (loop [messages []]
                   (if-let [m (try-bencode-decode @buffer)]
                     (do (vswap! buffer #(.slice % (.-length (bencode.encode m))))

                         (recur (conj messages (bencode-response->clj m))))
                     messages)))))))


(defn- status-to-keyword-set [message]
  (if (:status message)
    (update message :status #(set (map keyword %)))
    message))

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



; TODO make unit tests out of these
(comment
  ; error
  (go-try-log
    (-> (async/to-chan
          [{:err (str "Syntax error (ClassNotFoundException) compiling at "
                      "(REPL:1:7).\njava.lang.ProceissHandle\n")
            :id "7b3bb851-bb65-4019-b5e8-451f4343bb84"
            :session "cbb9533e-78f7-4528-bd1d-a4c29936cf99"}
           {:ex "class clojure.lang.Compiler$CompilerException"
            :id "7b3bb851-bb65-4019-b5e8-451f4343bb84"
            :root-ex "class clojure.lang.Compiler$CompilerException"
            :session "cbb9533e-78f7-4528-bd1d-a4c29936cf99"
            :status #{:eval-error}}
           {:id "7b3bb851-bb65-4019-b5e8-451f4343bb84"
            :session "cbb9533e-78f7-4528-bd1d-a4c29936cf99"
            :status #{:done}}])
        collect-eval-result
        <!
        pprint))

  ; value
  (go-try-log
    (-> (async/to-chan
          [{:id "f30969ec-8646-44ea-9d92-d5ecb7022329"
            :ns "user"
            :session "1797460c-4763-44cf-b9c3-20637f3d3004"
            :value "32118"}
           {:id "f30969ec-8646-44ea-9d92-d5ecb7022329"
            :session "1797460c-4763-44cf-b9c3-20637f3d3004"
            :status #{:done}}])
        collect-eval-result
        <!
        pprint))

  ; stdout and stderr
  (go-try-log
    (-> (async/to-chan
          [{:id "83e2ff56-b428-4c48-8d4a-54d1234e4a36"
            :out "qwer\n"
            :session "0ea06290-9c3b-418b-98fd-ae3db618bb07"}
           {:err "asdf\n"
            :id "83e2ff56-b428-4c48-8d4a-54d1234e4a36"
            :session "0ea06290-9c3b-418b-98fd-ae3db618bb07"}
           {:id "83e2ff56-b428-4c48-8d4a-54d1234e4a36"
            :ns "proto-repl.sample.core"
            :session "0ea06290-9c3b-418b-98fd-ae3db618bb07"
            :value "nil"}
           {:id "83e2ff56-b428-4c48-8d4a-54d1234e4a36"
            :session "0ea06290-9c3b-418b-98fd-ae3db618bb07"
            :status #{:done}}])
        collect-eval-result
        <!
        pprint))

  (async/go (.qwer 5)))


(defn get-value "Get the value from a channel of nREPL messages." [c]
  (safe-async-reduce (fn [result {:keys [value]}] (if (nil? value) result value)) nil c))
