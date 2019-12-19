(ns proto-repl.views.ink-repl-view
  (:require [clojure.string :as str]
            ["atom" :refer [CompositeDisposable Emitter]]
            [proto-repl.views.repl-view :refer [ReplView] :as rv]
            [proto-repl.ink :as ink]))

(def ^:private Highlights (js/require "../lib/highlights"))

(def ^:private console-uri "atom://proto-repl/console")
(def ^:private console-id "proto-repl")
(defonce ^:private highlighter (Highlights. #js{:registry js/atom.grammars}))

(defn- highlight [text]
  (.highlightSync highlighter #js{:fileContents text :scopeName "source.clojure"}))

(defn- html->element [html]
  (let [div (js/document.createElement "div")]
    (set! (.-innerHTML div) html)
    (.-firstChild div)))

(defn new-input [this text]
  (doto @(:console this)
    (.logInput)
    (.done)
    (.input)
    (-> .getInput .-editor (.setText text))))

(defrecord InkReplView [console emitter subscriptions]
  ReplView
  (on-did-close [_ callback] (.on emitter "proto-repl-ink-console:close" callback))

  (display-executed-code [this code]
    (let [editor (-> @console .getInput .-editor)
          old-text (.getText editor)]
      (.setText editor code)
      (new-input this old-text)))

  (execute-entered-text [this]
    (let [editor (-> @console .getInput .-editor)
          code (-> editor .getText str/trim)]
      (when (seq code)
        (-> editor (.setText ""))
        (when-not (.get js/atom.config "proto-repl.displayExecutedCodeInRepl")
          (rv/display-executed-code this code))
        (proto-repl.commands/execute-code code {:displayCode code :doBlock true}))))

  (clear [_] (-> @console .reset))
  (info [_ text] (some-> @console (.info text)))
  (stderr [_ text] (some-> @console (.stderr text)))
  (stdout [_ text] (some-> @console (.stdout text)))
  (doc [_ text] (some-> @console (.output #js{:type "info" :icon "book" :text text})))

  (result [_ text]
    (let [el (-> text
                 highlight
                 ; Replace non-breaking spaces so that code can be correctly copied and pasted.
                 (str/replace "&nbsp;" "&#32;")
                 html->element)]
      (-> el .-classList (.add "proto-repl-console"))
      (-> el .-style .-fontSize (set! (str (.get js/atom.config "editor.fontSize") "px")))
      (-> el .-style .-lineHeight (set! (.get js/atom.config "editor.lineHeight")))
      (.result @console el #js{:error false}))))

(defn- get-pane-items []
  (->> js/atom.workspace
       .getPanes
       (mapcat #(.-items %))))

(defn- get-repl-pane-item []
  (->> (get-pane-items)
       (filter #(= (.-id %) console-id))
       first))

(defn- show-repl []
  (if-let [item (get-repl-pane-item)]
    (.ensureVisible item)
    (.open js/atom.workspace console-uri #js{:split "right" :searchAllPanes true})))

(defn- make-console []
  (doto (.fromId ink/Console console-id)
    (.setTitle "Proto-REPL")
    (.activate)
    (.setModes #js[#js{:name "proto-repl" :default true :grammar "source.clojure"}])))

(defn- start-console [this]
  (let [c (make-console)]
    ; called when the user clicks the "Run" toolbar button
    (.onEval c #(proto-repl.commands/execute-text-entered-in-repl))
    (set! (.-destroy c)
          (fn [] (-> this :emitter (.emit "proto-repl-ink-console:close"))
                 (-> this :console (reset! nil))))
    (-> this :console (reset! c)))
  (show-repl))

(defn make-ink-repl-view []
  (let [emitter (Emitter.)
        subscriptions (CompositeDisposable.)
        console (atom nil)
        this (map->InkReplView {:console console
                                :emitter emitter
                                :subscriptions subscriptions})]
    (.add subscriptions (js/atom.workspace.addOpener
                          #(when (= % console-uri)
                             (.emit emitter "proto-repl-ink-console:open")
                             @console)))
    (start-console this)
    this))
