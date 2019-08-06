(ns proto-repl.plugin
  (:require [proto-repl.utils :refer [get-bind]]))

(def ^:private lodash (js/require "lodash"))
(def ^:private proto-repl (js/require "../lib/proto-repl"))
(def ^:private editor-utils (js/require "../lib/editor-utils"))


(defn state-merge! [m]
  (doseq [[k v] m]
    (lodash.set proto-repl (name k) v)))


(defn state-get [key] (get-bind proto-repl key))


(defn execute-code
  "Execute the given code string in the REPL. See Repl.executeCode for supported
  options."
  ([code] (execute-code code {}))
  ([code options]
   (some-> (state-get :repl) (.executeCode (str code) (clj->js (or options {}))))))


(defn execute-code-in-ns
  ([code] (execute-code-in-ns code {}))
  ([code options]
   (when-let [editor (.getActiveTextEditor js/atom.workspace)]
     (execute-code code (assoc options :ns (.findNsDeclaration editor-utils editor))))))


(defn info [text] (some-> (state-get :repl) (.info text)))
(defn stderr [text] (some-> (state-get :repl) (.stderr text)))
(defn stdout [text] (some-> (state-get :repl) (.stdout text)))
(defn doc [text] (some-> (state-get :repl) (.doc text)))


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
  (.registerCodeExecutionExtension (state-get :extensionsFeature) name callback))


(defn self-hosted? [] (.isSelfHosted (state-get :repl)))


(comment
  (do ::state)
  {:proto-repl.repl/state (atom {:process nil
                                 :repl-view nil
                                 :ink nil
                                 :extensions-feature nil})
   :proto-repl.repl/emitter (Emitter.)}

  (defprotocol ReplView
    (doc [this text])
    (info [this text])
    (on-did-close [this handler])
    (on-did-open [this handler])
    (stderr [this text])
    (stdout [this text]))

  (defrecord InkReplView [ink])

  (defrecord TextEditorReplView)

  (defprotocol Repl
    (doc [this text])
    (info [this text])
    (stderr [this text])
    (stdout [this text])

    (execute-code [this options])
    (exit [this])
    (get-repl-type [this])
    (interrupt [this])
    (running? [this])
    (self-hosted? [this]))

  (defrecord ProcessRepl [ink on-did-start]
    Repl
    (running? [this])))
