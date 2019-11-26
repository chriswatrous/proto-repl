(ns proto-repl.macros)

(defmacro this-fn [params & body]
  (let [this-sym (first params)
        other-params (vec (rest params))]
    `(fn ~other-params
       (cljs.core/this-as ~this-sym ~@body))))
