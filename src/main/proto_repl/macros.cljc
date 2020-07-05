(ns proto-repl.macros
  (:require [clojure.core.async]))

(defmacro this-fn [params & body]
  (let [this-sym (first params)
        other-params (vec (rest params))]
    `(fn ~other-params
       (cljs.core/this-as ~this-sym ~@body))))

(defmacro go-try-log [& body]
  `(cljs.core.async/go (try ~@body (catch :default err# (js/console.error err#)))))

(defmacro dochan!
  "Repeatedly executes body (presumably for side-effects) with values
  from a channel, exiting when the channel closes. Must be run inside
  of a go block. Returns nil."
  [binding & body]
  `(let [c# ~(second binding)]
     (loop []
       (when-let [~(first binding) (cljs.core.async/<! c#)]
         ~@body
         (recur)))))
