(ns proto-repl.views.connection-view
  (:require [clojure.string :as str]
            [clojure.core.async :as a]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [proto-repl.macros :refer-macros [go-try-log]]))


(def ^:private view
  [:div#connection-view.proto-repl.proto-repl-nrepl-connection-dialog
   [:h3.icon.icon-plug "nREPL connection"]
   [:div.block
    [:label "Host"]
    [:atom-text-editor#host-editor.editor.mini
     {:mini ""
      ; for some reason this key needs to be a string, not keyword
      "placeholder-text" "localhost"
      :data-encoding "utf8"
      :data-grammar "text plain null-grammar"}]]
   [:div.block
    [:label "Port"]
    [:atom-text-editor#port-editor.editor.mini
     {:mini ""
      ; for some reason this key needs to be a string, not keyword
      "placeholder-text" "1234"
      :data-encoding "utf8"
      :data-grammar "text plain null-grammar"}]]])


(defonce panel (atom nil))
(defonce previous-element (atom nil))


(defn destroy-panel []
  (some-> @panel .destroy))


(defn- get-element-by-id [id]
  (.getElementById js/window.document id))


(defn- next-focus []
  (.focus (get-element-by-id (if (.hasFocus (get-element-by-id "port-editor"))
                               "host-editor" "port-editor"))))


(defn- event-key? [event key & modifiers]
  (let [modifiers (set modifiers)]
    (and (= (.-key event) key)
         (= (.-altKey event) (boolean (:alt modifiers)))
         (= (.-shiftKey event) (boolean (:shift modifiers)))
         (= (.-metaKey event) (boolean (:meta modifiers)))
         (= (.-ctrlKey event) (boolean (:ctrl modifiers))))))


(defn- get-panel-data []
  {:host (-> (get-element-by-id "host-editor") .-innerText str/trim)
   :port (-> (get-element-by-id "port-editor") .-innerText str/trim)})


(defn- close-panel [c data]
  (go-try-log
    (when data (a/>! c data))
    (a/close! c))
  (destroy-panel)
  (.focus @previous-element))


(defn- handle-keydown [c event]
  (cond
    (or (event-key? event "Tab") (event-key? event "Tab" :shift))
    (do (.preventDefault event)
        (next-focus))

    (event-key? event "Escape")
    (do (.preventDefault event)
        (close-panel c nil))

    (event-key? event "Enter")
    (do (.preventDefault event)
        (close-panel c (get-panel-data))))
  nil)


(defn get-connection-info-from-user []
  (destroy-panel)
  (reset! previous-element (.-activeElement js/document))
  (let [div (js/document.createElement "div")
        c (a/chan)]
    (reset! panel (.. js/atom.workspace (addModalPanel #js{:item div})))
    (rdom/render view div)
    (.focus (get-element-by-id "host-editor"))
    (.addEventListener (get-element-by-id "connection-view") "keydown" #(handle-keydown c %))
    c))


(comment
  (destroy-panel)
  (get-connection-info-from-user)


  (.hasFocus (get-element-by-id "port-editor"))

  (def e (.getElementById js/window.document "port-editor"))
  (js/console.log show-connection-view)

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
