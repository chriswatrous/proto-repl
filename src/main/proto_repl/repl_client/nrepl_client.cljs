(ns proto-repl.repl-client.nrepl-client
  (:require [clojure.core.async :refer-macros [go]]))

(def ^:private bencode (js/require "bencode"))
(def ^:private net (js/require "net"))

(comment
  (js/console.log "qwer")

  (def s (net.connect #js {:host "localhost" :port 2345}))
  (.destroy s)
  (.on s "data"
    (fn [data]
      (js/console.log data)
      (def d data)))
       ; (try
       ;   (println "data event" (bencode->clj %))
       ;   (catch :default _
       ;     (js/console.log "data event" %)))))
  (.write s (clj->bencode {:op "ls-sessions"}) "binary")
  (js/Object.assign #js {} (bencode.decode d))

  (js/console.log (bencode.decode d "utf8")))


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
