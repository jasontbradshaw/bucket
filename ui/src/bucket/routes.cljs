(ns bucket.routes
  (:require [ajax.core :refer [GET]]
            [bucket.history :as history]
            [bucket.path :as path]
            [cljs.core.async :refer [put! chan]]
            [secretary.core :as secretary :refer-macros [defroute]]))

(defn process-files [files]
  "Given a list of files, processes them according to our preferences."
  (->> files
       (filter #(not (:is_hidden %)))
       (sort-by #(.toLowerCase (:name %))) ; case-insensitive name sort
       (sort-by #(if (:is_directory %) 0 1)) ; dirs first, then files
       (into [])))

;; TODO: refactor to not modify global state from here!
(defn update-files! [path]
  "Update the app state's `:files` key to the files under `path`."
  (GET (path/join "/files/" path "/")
       {:handler #(swap! bucket.core/app-state assoc :files (process-files %))
        :format :json
        :response-format :json
        :keywords? true
        :headers {"Content-Type" "application/json"}}))

(defroute home-path #"/home(.*)" [path]
  (update-files! path))

(defn navigate!
  "Navigate the history to a new URL."
  ([path] (navigate! path {}))
  ([path {:keys [replace state trigger title]
          :or {replace false
               state nil
               trigger true
               title (history/current-title)}}]
   (let [change-state! (if replace
                        history/replace-state!
                        history/push-state!)
         path (path/join path)]
     (change-state! state title path)
     (if trigger
       (secretary/dispatch! path)))))

;; configure and start history
(defn- dispatch-current-path! []
  "Dispatch to the current window pathname."
  (secretary/dispatch! (history/current-path)))

;; listen for state change events and dispatch when they happen
(defonce setup
  (do
    (.addEventListener js/window "popstate" dispatch-current-path!)
    (dispatch-current-path!)))
