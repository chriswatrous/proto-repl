(ns proto-repl.utils
  (:require [cljs.reader :as r]
            [clojure.edn :as edn]
            [fipp.edn :as fipp]
            [clojure.string :as str]
            [clojure.core.async :as async]))


(def ^:private lodash (js/require "lodash"))
(def ^:private edn-reader (js/require "../lib/proto_repl/edn_reader"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defn get-bind [obj key]
  (let [value (lodash.get obj (name key))]
    (if (lodash.isFunction value) (.bind value obj) value)))


(r/register-default-tag-parser!
  (fn [tag data] {(symbol "#" tag) data}))


(defn pretty-edn [edn-string]
  (try
    (with-out-str (fipp/pprint (edn/read-string edn-string)))
    (catch :default err edn-string)))


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


(defn reduce-kv-obj "Like reduce-kv but takes a js object instead of a map." [f init obj]
  (reduce (fn [result key] (f result key (aget obj key)))
          init
          (js/Object.keys obj)))


(defn obj->map "Convert a js object to a Clojure map with keyword keys." [obj]
  (->> obj
       (reduce-kv-obj (fn [m k v] (assoc! m (keyword k) v))
                      (transient {}))
       persistent!))


(defn map->obj "Convert a Clojure map to a js object." [m]
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


(defn get-config "Get a value from the package config." [key]
  (js/atom.config.get (str "proto-repl." (lodash.camelCase (name key)))))


(defn get-keybindings [command]
  (->> (.findKeyBindings js/atom.keymaps #js {:command (str "proto-repl:" (name command))})
       (mapv #(.-keystrokes %))))


(defn wrap-reducer-try-log
  "A transducer that catches and logs errors in the reducer function.
  This is to prevent the editor from crashing with
  \"DevTools was disconnected from the page.\""
  [rf]
  (fn ([] (try (rf)
               (catch :default err (js/console.error err))))
      ([result] (try (rf result)
                     (catch :default err (js/console.error err))))
      ([result input] (try (rf result input)
                           (catch :default err (js/console.error err)
                                               (reduced nil))))))


(defn safe-async-transduce
  "Like clojure.core.async/transduce but catches and logs exceptions in xform or f.
  This is to prevent the editor from crashing with
  \"DevTools was disconnected from the page.\""
  [xform f init ch]
  (async/transduce (comp wrap-reducer-try-log xform) f init ch))


(defn safe-async-reduce
  "Like clojure.core.async/reduce but catches and logs exceptions in xform or f.
  This is to prevent the editor from crashing with
  \"DevTools was disconnected from the page.\""
  [f init ch]
  (async/transduce wrap-reducer-try-log f init ch))


(defn js-typeof [data] (js* "(typeof ~{})" data))


(defn wrap-lookup [o]
  (reify
    ILookup
    (-lookup [_ k] (goog.object/get o (name k)))
    (-lookup [_ k not-found] (goog.object/get o (name k) not-found))))


(defn regex-or "Combine two regex patterns with '|'." [& patterns]
  (->> patterns
       (map #(str "(" (.-source %) ")"))
       (str/join "|")
       re-pattern))

(comment
  (regex-or #"ab" #"cd")
  (str #"ab"))


(defn global-regex "Return a new regex pattern with the 'g' flat set." [pattern]
  (js/RegExp. (.-source pattern) (str (.-flags pattern) "g")))


(defn comp+ "Compose functions, removing nil values" [& funcs]
  (apply comp (remove nil? funcs)))


(defn update-if-present [m k f & args]
  (if (contains? m k)
   (apply update m k f args)
   m))
