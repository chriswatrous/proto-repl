(ns proto-repl.integration.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.set :refer [subset?]]
            [cljs.test :refer [is deftest]]
            [cljs.core.async]
            [promesa.core :as p]
            [promesa.async-cljs]
            ["util" :refer [inspect]]
            [proto-repl.commands :refer [state repl]]
            [proto-repl.editor-utils :refer [get-active-text-editor]])
  (:require-macros [cljs.core.async :refer [go]]
                   [promesa.core :as p :refer [await]]
                   [promesa.async-cljs :refer [async]]
                   [proto-repl.test-utils.macros :refer [is!]]))


(defn submap?
  "Is map1 a submap of map2?"
  [map1 map2]
  (subset? (set map1) (set map2)))


(defn run-command [name]
  (js/atom.commands.dispatch (js/atom.views.getView js/atom.workspace) name))


(defn clear-repl []
  (run-command "proto-repl:clear-repl"))


(defn ensure-single-cursor []
  (let [editor (get-active-text-editor)]
    (run! #(.setBufferPosition % #js [0 0]) editor.cursors)
    (.mergeCursors editor)))


(defn set-cursor-position [{:keys [row column]}]
  (ensure-single-cursor)
  (let [cursor (-> (get-active-text-editor) .getLastCursor)]
    (.setBufferPosition cursor #js [row column])))


(defn move-cursor [{:keys [left right up down]}]
  (let [cursor (-> (get-active-text-editor) .getLastCursor)]
    (when left (.moveLeft cursor left))
    (when right (.moveRight cursor right))
    (when up (.moveUp cursor up))
    (when down (.moveDown cursor down))))


(defn wait-for-condition [f & args]
  (async
    (let [end (+ (js/Date.now) 1000)]
      (while (not (apply f args))
        (if (> (js/Date.now) end)
          (throw (js/Error. "timeout waiting for condition")))
        (await (p/delay 10))))))


(defn get-cells []
  (-> @repl .-replView .-console .-items
      (js->clj :keywordize-keys true)))


(defn get-outputs []
  (-> @repl .-replView .-console .-items
      (js->clj :keywordize-keys true)
      (->> (filterv #(not= (:type %) "input")))))


(defn wait-for-output []
  (wait-for-condition #(> (count (get-outputs)) 0)))


(defn get-single-result []
  (let [results (filter #(= (:type %) "result") (get-cells))]
    (assert (= (count results) 1))
    (first results)))


(defn get-single-doc-cell []
  (let [results (filter #(= (:type %) "result") (get-cells))]))


(comment
  (get-cells)
  (filter (partial submap? {:type "info" :icon "book"}) (get-cells)))


(defn get-result-text [cell] (.-innerText (:result cell)))


(defn set-code [code]
  (let [editor (get-active-text-editor)
        buffer (.getBuffer editor)]
    (is! (str/ends-with? (-> buffer .-file .-path) "proto_repl/sample/core.clj"))
    (.setText buffer (str "(ns proto-repl.sample.core)\n\n" code))))


(defn set-up-test [code]
  (clear-repl)
  (set-code code)
  (set-cursor-position {:row 2 :column 0}))


(defn run-tests [& tests]
  (async
    (js/console.log "starting tests")
    (-> (loop [tests tests
               results []]
          (let [[test & tests] tests
                results (conj results (try (await (test)) :passed
                                           (catch :default err (js/console.error err) :failed)))]
            (if (seq tests) (recur tests results) results)))
        frequencies str js/console.log)
    (js/console.log "done")))


(defn test-execute-top-block []
  (async
    (set-up-test '(+ 1 1))
    (run-command "proto-repl:execute-top-block")
    (await (wait-for-output))
    (is! (= (get-result-text (get-single-result)) "2\n"))))


(defn test-execute-top-block-inside-comment []
  (async
    (set-up-test '(comment (+ 1 1)))
    (move-cursor {:right 9})
    (run-command "proto-repl:execute-top-block")
    (await (wait-for-output))
    (is! (= (get-result-text (get-single-result)) "2\n"))))


(defn test-print-var-documentation []
  (async
    (set-up-test '(comment))
    (move-cursor {:right 1})
    (run-command "proto-repl:print-var-documentation")
    (await (wait-for-output))
    (is! (= (get-result-text (get-single-doc-cell)) "2\n"))))

(comment
  (run-tests
    test-execute-top-block
    test-execute-top-block-inside-comment)

  (def e (get-active-text-editor))
  (-> e js/Object.getOwnPropertyNames sort)
  (-> e js/Object.getPrototypeOf js/Object.getOwnPropertyNames sort)
  (-> e
      js/Object.getPrototypeOf
      js/Object.getOwnPropertyNames
      (->> (filter #(str/includes? (str/lower-case %) "buffer")))
      sort)

  (def b (.getBuffer e))
  (-> b js/Object.getOwnPropertyNames sort)
  (-> b js/Object.getPrototypeOf js/Object.getOwnPropertyNames sort)

  (def f (.-file b))
  (-> f js/Object.getOwnPropertyNames sort)
  (-> f js/Object.getPrototypeOf js/Object.getOwnPropertyNames sort)

  (.setText b (str "(ns proto-repl.sample.core)\n"
                   "\n"
                   "(do :x)\n"))

  (-> e.cursor js/Object.getPrototypeOf js/Object.getOwnPropertyNames sort)
  (-> e.cursor js/Object.getOwnPropertyNames sort)
  (do e.cursors)
  (def c (-> e .-cursors (aget 0)))

  (-> c js/Object.getOwnPropertyNames sort)
  (-> c js/Object.getPrototypeOf js/Object.getOwnPropertyNames sort)

  (println "qwerQwerqwe"))
