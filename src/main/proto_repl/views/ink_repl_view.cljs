(ns proto-repl.views.ink-repl-view
  (:require [clojure.string :as str]
            ["atom" :refer [CompositeDisposable Emitter]]
            [proto-repl.views.repl-view :refer [ReplView] :as rv]
            [proto-repl.ink :as ink]))

(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private Highlights (js/require "../lib/highlights"))

(def ^:private console-uri "atom://proto-repl/console")
(def ^:private nbsp "\u00a0")
(defonce ^:private highlighter (Highlights. #js{:registry js/atom.grammars}))


(defn- highlight [text]
  (.highlightSync highlighter #js{:fileContents text :scopeName "source.clojure"}))


(defn- html->element [html]
  (let [div (js/document.createElement "div")]
    (set! (.-innerHTML div) html)
    (.-firstChild div)))


(defrecord InkReplView [old-view emitter]
  ReplView
  (on-did-close [_ callback] (.on emitter "proto-repl-ink-console:close" callback))

  (execute-entered-text [_]
    (let [editor (-> old-view .-console .getInput .-editor)]
      (when-let [code (-> editor .getText str/trim)]
        (when-not (.get js/atom.config "proto-repl.displayExecutedCodeInRepl")
          (-> old-view (.displayExecutedCode code)))
        (proto-repl.commands/execute-code code {:displayCode code :doBlock true}))))

  (clear [_] (-> old-view .-console .reset))
  (info [_ text] (some-> old-view .-console (.info text)))
  (stderr [_ text] (some-> old-view .-console (.stderr text)))
  (stdout [_ text] (some-> old-view .-console (.stdout text)))

  (doc [_ text]
    (some-> old-view .-console (.output #js{:type "info" :icon "book" :text text})))

  (result [_ text]
    (let [el (-> text
                 highlight
                 ; Replace non-breaking spaces so that code can be correctly copied and pasted.
                 (str/replace nbsp " ")
                 html->element)]
      (-> el .-classList (.add "proto-repl-console"))
      (-> el .-style .-fontSize (set! (str (.get js/atom.config "editor.fontSize") "px")))
      (-> el .-style .-lineHeight (set! (.get js/atom.config "editor.lineHeight")))
      (-> old-view .-console (.result el #js{:error false})))))


(defn- set-console-title [console title]
  (set! (.-getTitle console) (fn [] title))
  (-> console .-emitter (.emit "did-change-title" title)))


(defn- start-console [old-view]
  (let [console (.fromId ink/Console "proto-repl")]
    (set! (.-console old-view) console)
    (set-console-title console "Proto-REPL")
    (.activate console)
    (.onEval console #(.executeEnteredText old-view %))
    (.setModes console #js[#js{:name "proto-repl" :default true :grammar "source.clojure"}])
    (set! (.-destroy console)
          (fn [] (-> old-view .-emitter (.emit "proto-repl-ink-console:close"))
                 (set! (.-console old-view) nil)))
    (.open js/atom.workspace console-uri #js{:split "right" :searchAllPanes true})))


(defn make-ink-repl-view []
  (let [emitter (Emitter.)
        old-view (InkConsole.)
        subscriptions (CompositeDisposable.)
        view (map->InkReplView {:old-view old-view
                                :emitter emitter})]
    (js/Object.assign old-view
      #js{:ink ink/ink
          :emitter emitter
          :subscriptions subscriptions
          :highlighter (Highlights. #js{:registry js/atom.grammars})})
    (.add subscriptions (js/atom.workspace.addOpener
                          #(when (= % console-uri)
                             (.emit emitter "proto-repl-ink-console:open")
                             (.-console old-view))))
    (start-console old-view)
    view))
