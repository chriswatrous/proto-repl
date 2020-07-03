(ns proto-repl.repl-client.nrepl-client
  (:require [clojure.core.async :as a
                                :refer [chan buffer put! close!]
                                :refer-macros [go go-loop]]))

(def ^:private bencode (js/require "bencode"))
(def ^:private net (js/require "net"))

(declare message-chan)
(declare s)
(declare c)

(comment
  (do
    (some-> message-chan close!)
    (some-> s .destroy)
    (def message-chan (chan (buffer 1) buffers->messages))
    (def s
      (doto (net.createConnection #js {:host "localhost" :port 2345})
        ; important
        (.on "ready" #(js/console.log "ready"))
        (.on "close" #(close! message-chan))
        (.on "data" #(put! message-chan %))
        (.on "error" #(js/console.log "error" %&))
        ; extra
        (.on "lookup" #(js/console.log "lookup" %&))
        (.on "connect" #(js/console.log "connect" %&))
        (.on "timeout" #(js/console.log "timeout" %&))
        (.on "drain" #(js/console.log "drain" %&))
        (.on "end" #(js/console.log "end" %&))))
    (go-loop []
      (when-let [message (<! message-chan)]
        (println "from channel:" message)
        (recur))
      (println "channel closed")))

  (send {:op "ls-sessions"}))


(defn- bencode-response->clj [data]
  "Convert bencode.decode response to cljs with keyword keys.
  bencode.decode returns Dict objects instead of plain objects so js->clj won't work."
  (cond
    (js/Array.isArray data) (mapv bencode-response->clj data)
    (= (js* "(typeof ~{})" data) "object") (->> data
                                                js/Object.entries
                                                (map (fn [[k v]] [(keyword k)
                                                                  (bencode-response->clj v)]))
                                                (into {}))
    :else data))

(defn- clj->bencode "Encode cljs data to bencode." [data]
  (bencode.encode (clj->js data) "utf8"))

(defn- bencode->clj "Decode bencode data to cljs data." [buffer]
  (-> buffer
      (bencode.decode "utf8")
      bencode-response->clj))

(defn- try-bencode->clj
  "Decode bencode data to cljs data or return nil if the buffer doesn't
  contain a complete message."
  [buffer]
  (try
    (bencode->clj buffer)
    ; An exception will be thrown if the buffer is empty or doesn't
    ; contain a complete bencode message.
    (catch :default _)))

(defn- send [data]
  (.write s (clj->bencode data) "binary"))

(defn- buffers->messages "A stateful transducer that converts Buffers to nREPL messages." [rf]
  (let [buffer (volatile! (js/Buffer.from ""))]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (vswap! buffer #(js/Buffer.concat #js [% input]))
       (reduce rf result
               (loop [messages []]
                 (if-let [m (try-bencode->clj @buffer)]
                   (do (vswap! buffer #(.slice % (.-length (clj->bencode m))))
                       (recur (conj messages m)))
                   messages)))))))
