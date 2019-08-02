(ns proto-repl.utils)

(def ^:private lodash (js/require "lodash"))


(defn get-bind [obj key]
  (let [value (lodash.get obj (name key))]
    (if (lodash.isFunction value) (.bind value obj) value)))
