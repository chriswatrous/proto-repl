(ns proto-repl.utils)

(def ^:private lodash (js/require "lodash"))
(def ^:private edn-reader (js/require "../lib/proto_repl/edn_reader"))


(defn get-bind [obj key]
  (let [value (lodash.get obj (name key))]
    (if (lodash.isFunction value) (.bind value obj) value)))


(defn pretty-edn [edn-string]
  (try (.pretty_print edn-reader edn-string)
       (catch :default err edn-string)))


(defn edn->display-tree
  "Parses the edn string and returns a displayable tree. A tree is an array
  whose first element is a string of the root of the tree. The rest of the
  elements are branches off the root. Each branch is another tree. A leaf is
  represented by a vector of one element."
  [edn-string]
  (try (.to_display_tree edn-reader edn-string)
       (catch :default err #js [edn-string])))
