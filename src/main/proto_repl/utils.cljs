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
       (catch :default err #js[edn-string])))

(defn js->edn
  "Converts a Javascript object to EDN. This is useful when you need to take a
  JavaScript object and pass representation of it to Clojure running in the JVM."
  [js-data]
  (.js_to_edn edn-reader js-data))

(defn edn-saved-values->display-trees [edn-string]
  (try (.saved_values_to_display_tree edn-reader edn-string)
       (catch :default err
         (js/console.log err)
         #js[])))

(defn obj->map [obj]
  (loop [keys (js/Object.keys obj)
         out (transient {})]
    (let [f (first keys)
          r (next keys)
          out (assoc! out (keyword f) (aget obj f))]
      (if r (recur r out) (persistent! out)))))

(defn map->obj [m]
  (let [out #js{}]
    (doseq [[k v] m]
      (aset out (if (keyword? k) (name k) k) v))
    out))

(defn make-js-class [{:keys [name extends constructor static instance]}]
  (when name (js/Object.defineProperty constructor "name" #js{:value name}))
  (when extends (js/Object.assign constructor extends))
  (js/Object.assign constructor (map->obj static))
  (set! (.-prototype constructor)
        (js/Object.assign (if extends (js/Object.create (.-prototype extends)) #js{})
                          #js{:constructor constructor}
                          (map->obj instance)))
  constructor)

(defn empty->nil [o]
  (if (empty? o) nil o))
