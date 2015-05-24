(ns bucket.components.nav
  (:require [bucket.routes :as routes]
            [bucket.fast-path :as fast-path]
            [om-tools.core :refer-macros [defcomponent]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defcomponent nav-breadcrumb [path-segment owner]
  (render [this]
    (html
      (let [link (:href path-segment)]
        [:a {:class "nav-breadcrumb"
             :href link
             :on-mouse-down #(fast-path/notify! link)
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
