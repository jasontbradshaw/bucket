(ns bucket.path
  (:require [clojure.string :as string]))

(defn join [& segments]
  "Join the given path segments together, compressing `/`."
  (string/replace (string/join "/" segments) #"//+" "/"))

(defn split [path]
  "Split the given path into segments, treating multiple `/` as one."
  (filterv #(not= "" %) (string/split path #"/+")))

(defn segmentize [path]
  "Given a path string, turns it into a vector of segment hashes."
  (->> (split path)
       (reduce #(conj %1 (conj (last %1) %2)) [["browse"]])
       (map (fn [segments]
               (let [href (str "/" (apply join segments) "/")]
                 {:href href
                  :name (last segments)})))
       (into [])))
