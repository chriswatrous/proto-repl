(ns proto-repl.test-utils.macros
  (:require [cljs.test :refer [is]]))


(defmacro is! [& forms]
  `(let [text# (with-out-str (is ~@forms))]
      (when (seq text#) (throw (js/Error. text#)))))
