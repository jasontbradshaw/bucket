(ns bucket.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [bucket.routes :as routes]
            [bucket.history :as history]
            [bucket.path :as path]
            [bucket.state :as state]
            [bucket.util :as util]
            [figwheel.client :as figwheel]
            [cljs.core.async :refer [put! chan <!]]
            [devtools.core :as devtools]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]))

;; TODO: put these in a module that only gets loaded during dev mode
(enable-console-print!)
(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})
(devtools/install!)

;; a single file
(defcomponent file [file owner]
  (render [this]
    (html [:div {:class "file"
                 :data-mime-type (:mime_type file)}
           [:img {:class "file-icon"
                  :src (util/icon-path-for-mime-type (:mime_type file))}]
           (let [link (path/join (history/current-path)
                                 (str (:name file)
                                      (if (:is_directory file) "/" "")))]
             [:a {:href link
                  :class "file-name"
                  :on-click (fn [e]
                              (if (:is_directory file)
                                (do
                                  (.preventDefault e)
                                  (routes/navigate! link))))}
              (:name file)])])))

;; a list of files
(defcomponent file-list [global owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file (:files global) {:key :name})])))

;; start the app
(defonce root (.querySelector js/document "main"))
(om/root file-list state/global {:target root})
