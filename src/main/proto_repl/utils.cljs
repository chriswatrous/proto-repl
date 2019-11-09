(ns proto-repl.utils
  (:require [cljs.reader :as r]
            [clojure.edn :as edn]
            [fipp.edn :as fipp]))

(def ^:private lodash (js/require "lodash"))
(def ^:private edn-reader (js/require "../lib/proto_repl/edn_reader"))


(defn get-bind [obj key]
  (let [value (lodash.get obj (name key))]
    (if (lodash.isFunction value) (.bind value obj) value)))


(r/register-default-tag-parser!
  (fn [tag data] {(symbol "#" tag) data}))


(defn pretty-edn [edn-string]
  (try
    (with-out-str (fipp/pprint (edn/read-string edn-string)))
    (catch :default err
      (js/console.error "Error formatting repl result:" err)
      edn-string)))


(defn edn->display-tree
  "Parses the edn string and returns a displayable tree. A tree is an array
  whose first element is a string of the root of the tree. The rest of the
  elements are branches off the root. Each branch is another tree. A leaf is
  represented by a vector of one element."
  [edn-string]
  (try (.to_display_tree edn-reader edn-string)
       (catch :default err #js [edn-string])))


(defn js->edn
  "Converts a Javascript object to EDN. This is useful when you need to take a
  JavaScript object and pass representation of it to Clojure running in the JVM."
  [js-data]
  (.js_to_edn edn-reader js-data))


(defn edn-saved-values->display-trees [edn-string]
  (try (.saved_values_to_display_tree edn-reader edn-string)
       (catch :default err
         (js/console.log err)
         #js [])))
