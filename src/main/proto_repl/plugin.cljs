(ns proto-repl.plugin
  (:require [proto-repl.utils :refer [get-bind]]))

(def ^:private lodash (js/require "lodash"))
(def ^:private proto-repl (js/require "../lib/proto-repl"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defn state-merge! [m]
  (doseq [[k v] m]
    (lodash.set proto-repl (name k) v)))


(defn state-get [key] (get-bind proto-repl key))


(defn execute-code [code options]
  (some-> (state-get :repl) (.executeCode code (clj->js (or options {})))))


(defn execute-code-in-ns [code options]
  (when-let [editor (.getActiveTextEditor js/atom.workspace)]
    (let [ns- (.findNsDeclaration editor-utils editor)
          options (clj->js (or options {}))
          options (if ns- (lodash.set options "ns" ns-))]
      (execute-code code options))))


(state-merge! {:executeCode execute-code
               :executeCodeInNs execute-code-in-ns})
