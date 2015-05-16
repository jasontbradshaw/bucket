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

(defcomponent nav-breadcrumb [path-segment owner]
  (render [this]
    (html
      (let [link (:href path-segment)]
        [:a {:class "nav-breadcrumb"
             :href link
             :on-click (fn [e]
                         (do
                           (.preventDefault e)
                           (routes/navigate! link)))}
         (:name path-segment)]))))

(defcomponent nav-breadcrumbs [segments owner]
  (render [this]
    (html
      [:div {:class "nav-breadcrumbs"}
       (interpose [:img {:class "nav-breadcrumb-separator"
                         :src "/resources/images/angle-right.svg"}]
                  (om/build-all nav-breadcrumb segments {:key :href}))])))

;; the overall app navigation bar
(defcomponent nav [segments owner]
  (render [this]
    (html [:nav
            (om/build nav-breadcrumbs segments)])))

(defcomponent file [file owner]
  (render [this]
    (html [:div {:class "file"}
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
              [:img {:class "file-icon"
                     :src (util/icon-path-for-file file)}]
              (:name file)])])))

(defcomponent file-list [files owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file files {:key :name})])))

(defcomponent app [global owner]
  (render [this]
    (html
      [:main
       (om/build nav (:path global))
       (om/build file-list (:files global))])))

;; start the app
(defonce root (.querySelector js/document "body"))
(om/root app state/global {:target root})
