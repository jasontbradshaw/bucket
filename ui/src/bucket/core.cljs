(ns bucket.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [bucket.routes :as routes]
            [bucket.history :as history]
            [bucket.path :as path]
            [bucket.state :as state]
            [bucket.util :as util]
            [cljs.core.async :refer [put! chan <!]]
            [clojure.string :as string]
            [devtools.core :as devtools]
            [om-tools.core :refer-macros [defcomponent]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

;; turn console.log into the default print function
(enable-console-print!)

;; TODO: put this in a module that only gets loaded during dev mode
(devtools/install!)

;; the overall app navigation bar
(defcomponent nav [nav owner]
  (render [this]
    (html [:nav])))

(defcomponent file [file owner]
  (render [this]
    (html [:div {:class "file"
                 :data-mime-type (:mime_type file)}
           (let [link (if (:is_directory file)
                        (path/join (history/current-path) (:name file) "/")
                        (string/replace
                          (path/join (history/current-path) (:name file))
                          #"^/home/" "/files/"))]
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
