(ns proto-repl.views.ink-repl-view
  (:require ["atom" :refer [CompositeDisposable Emitter]]
            [proto-repl.views.repl-view :refer [ReplView]]
            [proto-repl.ink :as ink]))

(def ^:private InkConsole (js/require "../lib/views/ink-console"))
(def ^:private Highlights (js/require "../lib/highlights"))

(def ^:private console-uri "atom://proto-repl/console")


(defrecord InkReplView [old-view emitter]
  ReplView
  (on-did-close [_ callback] (.on emitter "proto-repl-ink-console:close" callback))
  (clear [_] (-> old-view .-console .reset))
  (info [_ text] (some-> old-view .-console (.info text)))
  (stderr [_ text] (some-> old-view .-console (.stderr text)))
  (stdout [_ text] (some-> old-view .-console (.stdout text)))
  (doc [_ text]
    (some-> old-view .-console (.output #js{:type "info" :icon "book" :text text}))))


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
  (let [old-view (InkConsole.)]
    (set! (.-ink old-view) ink/ink)
    (set! (.-emitter old-view) (Emitter.))
    (set! (.-subscriptions old-view) (CompositeDisposable.))
    (-> old-view .-subscriptions
        (.add (.addOpener js/atom.workspace
                #(when (= % console-uri)
                   (-> old-view .-emitter (.emit "proto-repl-ink-console:open"))
                   (.-console old-view)))))
    (set! (.-highlighter old-view) (Highlights. #js{:registry js/atom.grammars}))
    (start-console old-view)

    (map->InkReplView {:old-view old-view
                       :emitter (.-emitter old-view)})))
