(ns proto-repl.views.connection-view-2
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(comment
  (+ 1 1)
  (js/alert "qsadf")

  (macroexpand '
    (.. js/atom -workspace (addModalPanel #js {:item div})))

  (let [div (js/document.createElement "div")
        panel (.. js/atom -workspace (addModalPanel #js {:item div}))]
    (rdom/render [[:h2 "qwer"]])))
