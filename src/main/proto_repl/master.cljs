(ns proto-repl.master
  (:require [proto-repl.repl :as r]))

(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defonce state (atom {}))


(defn execute-code
  "Execute the given code string in the REPL. See Repl.executeCode for supported
  options."
  ([code] (execute-code code {}))
  ([code options]
   (some-> @state :repl2 (r/execute-code (str code) (or options {})))))


(defn execute-code-in-ns
  ([code] (execute-code-in-ns code {}))
  ([code options]
   (when-let [editor (.getActiveTextEditor js/atom.workspace)]
     (execute-code code (assoc options :ns (.findNsDeclaration editor-utils editor))))))


(defn info [text] (some-> @state :repl2 (r/info text)))
(defn stderr [text] (some-> @state :repl2 (r/stderr text)))
(defn stdout [text] (some-> @state :repl2 (r/stdout text)))
(defn doc [text] (some-> @state :repl2 (r/doc text)))


(defn register-code-execution-extension
  "Registers a code execution extension with the given name and callback function.

  Code execution extensions allow other Atom packages to extend Proto REPL
  by taking output from the REPL and redirecting it for other uses like
  visualization.
  Code execution extensions are triggered when the result of code execution is
  a vector with the first element is :proto-repl-code-execution-extension. The
  second element in the vector should be the name of the extension to trigger.
  The name will be used to locate the callback function. The third element in
  the vector will be passed to the callback function."
  [name callback]
  (-> @state :extensionsFeature (.registerCodeExecutionExtension name callback)))


(defn self-hosted? [] (-> @state :repl2 r/self-hosted?))
