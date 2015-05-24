(ns bucket.util
  (:require [bucket.path :as path]
            [bucket.history :as history]
            [clojure.string :as string]
            [cljs.reader :refer [read-string]]))

(defn- icon-path [s]
  "Returns a path to the icon with the given name."
  (js/encodeURI (path/join "/resources/images/" (str s ".svg"))))

(defn- icon-path-for-mime-type [t]
  "Given a MIME type string, returns the path to an appropriate icon image."
  (icon-path
    (cond (= "application/pdf" t) "file-pdf"
          (= "application/zip" t) "file-archive"
          (re-find #"^archive" t) "file-archive"
          (re-find #"^audio" t) "file-audio"
          (re-find #"^image" t) "file-image"
          (re-find #"^text" t) "file-text"
          (re-find #"^video" t) "file-video"
          :else "file")))

(defn icon-path-for-file [f]
  "Given a file, returns the path to an appropriate icon image for it."
  (cond (:is_directory f) (icon-path "folder")
        (:is_code f) (icon-path "file-code")
        :else (icon-path-for-mime-type (:mime_type f))))

(defn thumbnail-path-for-file [f]
  "Given a file, returns the path to an appropriate thumbnail image for it."
  (js/encodeURI
    (string/replace
      (path/join (history/current-path) (:name f))
      #"^/browse" "/thumbnails/")))
