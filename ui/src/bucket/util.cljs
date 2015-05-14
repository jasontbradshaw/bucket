(ns bucket.util
  (:require [clojure.string :as string]
            [cljs.reader :refer [read-string]]))

(defn- str-or-int [s]
  "If the given string is all-numeric, returns an integer, otherwise a string."
  (if (re-matches #"^\d+$" s)
    (read-string s) ; safe since we know this is an int
    s))


(defn str->alphanum [s]
  "Given a string, turns it into a vector of non-numeric character groups and
   all-numeric character groups, with the latter represented as numbers. This
   is useful for sorting strings in a human-ordered fashion, rather than purely
   lexicographically."
  (map
    #(str-or-int (string/join %))

    ;; turn the string into vectors of digit/non-digit characters
    (partition-by #(if (re-matches #"\d" %) :digit :other) s)))

(defn icon-path-for-mime-type [t]
  "Given a MIME type string, returns the path to an appropriate icon image."
  (str
    "/resources/images/"
    (cond (= "inode/directory" t) "folder"
          (= "application/pdf" t) "file-pdf"
          (= "application/zip" t) "file-archive"
          (re-find #"^archive" t) "file-archive"
          (re-find #"^audio" t) "file-audio"
          (re-find #"^image" t) "file-image"
          (re-find #"^text" t) "file-text"
          (re-find #"^video" t) "file-video"
          :else "file")
    ".svg"))
