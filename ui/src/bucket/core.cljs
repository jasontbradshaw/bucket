(ns bucket.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [bucket.routes :as routes]
            [figwheel.client :as figwheel]
            [clojure.string :as string]
            [cljs.core.async :refer [put! chan <!]]
            [devtools.core :as devtools]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]))

;; TODO: put these in a module that only gets loaded during dev mode
(enable-console-print!)
(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})
(devtools/install!)

(defonce app-state (atom {
  ;; the list of files to display
  :files []
}))

;; show app state changes for simpler debugging
(add-watch app-state :debug-watcher
           (fn [_ _ _ _]
             (.log js/console "State:" (clj->js @app-state))))

;; the root element of our application
(defonce root (.querySelector js/document "main"))

;; a single file
(defcomponent file [file owner]
  (render [this]
          (html [:div {:class "file"
                       :data-mime-type (:mime_type file)}
                  [:a {:href "#"
                       :class "file-name"
                       :on-click (fn [e]
                                   (if (:is_directory file)
                                     (do
                                       (.preventDefault e)
                                       (routes/navigate!
                                         (str
                                           (.. js/window -location -pathname)
                                           "/"
                                           (:name file))))))}
                   (:name file)]])))

;; a list of files
(defcomponent file-list [app-state owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file (:files app-state) {:key :name})])))

(om/root file-list app-state {:target root})
