(ns proto-repl.macros
  (:require [clojure.core.async]))

(defmacro this-fn [params & body]
  (let [this-sym (first params)
        other-params (vec (rest params))]
    `(fn ~other-params
       (cljs.core/this-as ~this-sym ~@body))))

(defmacro go-try-log
  "Go block that catches and logs errors.
  This is to prevent the editor from crashing with
  \"DevTools was disconnected from the page.\""
  [& body]
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

(defmacro when-let+
  "Multiple bindings version of whenn-let taken from
  https://clojuredocs.org/clojure.core/when-let
  and improved slightly"
  [bindings & body]
  (if (seq bindings)
    `(when-let ~(vec (take 2 bindings))
       (when-let+ ~(vec (drop 2 bindings)) ~@body))
    `(do ~@body)))

(defmacro template-fill
  "Wrap body in (do ...), convert to string, and string replace placeholders"
  [placeholders & body]
  `(reduce (fn [result# [placeholder# value#]]
             (clojure.string/replace result# placeholder# value#))
           ~(str (apply list 'do body))
           ~(mapv (fn [p] [(name p) p]) placeholders)))

(defmacro go-promise
  "A go block that returns a JavaScript promise. The return value of
  body will be the resolved value of the promise. Any error thrown in
  body will reject the promise."
  [& body]
  `(js/Promise.
     (fn [resolve# reject#]
       (cljs.core.async/go
         (try
           (resolve# (do ~@body))
           (catch :default err# (reject# err#)))))))
