(ns proto-repl.sample.test
  (:require [clojure.test :refer :all]))

; run-test-under-cursor (cmd-alt-t)
; run-tests-in-ns (cmd-alt-x)
; run-all-tests (cmd-alt-a)

(deftest something
  (is (= 1 1)))

(deftest something-else
  (is (= 1 0)))
