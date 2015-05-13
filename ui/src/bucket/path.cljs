(ns bucket.path
  (:require [clojure.string :as string]))

(defn join [& segments]
  "Join the given path segments together, compressing `/`."
  (string/replace (string/join "/" segments) #"//+" "/"))
