(ns bucket.components.files
  (:require [bucket.routes :as routes]
            [bucket.fast-path :as fast-path]
            [bucket.history :as history]
            [bucket.path :as path]
            [bucket.util :as util]
            [clojure.string :as string]
            [om-tools.core :refer-macros [defcomponent]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defcomponent file [file owner]
  (render [this]
    (html [:div {:class "file"
                 :data-mime-type (:mime_type file)}
           (let [link (if (:is_directory file)
                        (path/join (history/current-path) (:name file) "/")
                        (string/replace
                          (path/join (history/current-path) (:name file))
                          #"^/home/" "/files/"))
                 notify! (if (:is_directory file)
                           #(fast-path/notify! link)
                           identity)]
             [:a {:href link
                  :class "file-name"
                  :on-mouse-down notify!
                  :on-click (fn [e]
                              (if (:is_directory file)
                                (do
                                  (.preventDefault e)
                                  (routes/navigate! link))))}
              (if (re-find #"^(?:image|video)/" (:mime_type file))
                [:div {:class "file-thumbnail"
                       :style {:background-image
                               (str "url(" (util/thumbnail-path-for-file file) ")")}}]
                [:img {:class "file-icon"
                       :src (util/icon-path-for-file file)}])
              (:name file)])])))

(defcomponent file-list [files owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file files {:key :name})])))
