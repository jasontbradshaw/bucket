(ns bucket.util
  (:require [clojure.string :as string]))

(defn- str-or-int [s]
  "If the given string is all-numeric, returns an integer, otherwise a string."
  (if (re-matches #"^\d+$" s)
    (cljs.reader/read-string s) ; safe since we know this is an int
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
