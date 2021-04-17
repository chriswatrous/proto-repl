(ns proto-repl.views.connection-view-2
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))


(def ^:private view
  [:div.proto-repl.proto-repl-nrepl-connection-dialog
   [:h3.icon.icon-plug "nREPL connection"]
   [:div.block
    [:label {"qwer-asdf" "abcd"} "Host"]
    [:atom-text-editor#host-editor.editor.mini
     {:tabindex "1"
      :mini ""
      "placeholder-text" "localhost"
      :data-encoding "utf8"
      :data-grammar "text plain null-grammar"}]]
   [:div.block
    [:label "Port"]
    [:atom-text-editor#port-editor.editor.mini
     {:tabindex "2"
      :mini ""
      "placeholder-text" "1234"
      :data-encoding "utf8"
      :data-grammar "text plain null-grammar"}]]])


(defonce panel (atom nil))


(defn show-connection-view []
  (let [div (js/document.createElement "div")]
    (reset! panel (.. js/atom.workspace (addModalPanel #js{:item div})))
    (rdom/render view div)))


(comment
  (.destroy @panel)

  (def e (.getElementById js/window.document "port-editor"))

  (.hasFocus e)
  (.focus e)

  (js/Object.keys e)
  (-> e
      js/Object.getPrototypeOf
      js/Object.getPrototypeOf
      js/Object.getPrototypeOf
      js/Object.getPrototypeOf
      js/Object.getPrototypeOf
      js/Object.getPrototypeOf
      js/Object.getOwnPropertyNames
      sort))
