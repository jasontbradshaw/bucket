(ns bucket.components.app
  (:require [bucket.components.files :refer [file-list]]
            [bucket.components.nav :refer [nav]]
            [om-tools.core :refer-macros [defcomponent]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defcomponent app [global owner]
  (render [this]
    (html
      [:main
       (om/build nav (:path global))
       (om/build file-list (:files global))])))
