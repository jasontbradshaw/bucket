(ns bucket.routes
  (:require [ajax.core :refer [GET]]
            [bucket.history :as history]
            [bucket.path :as path]
            [bucket.state :as state]
            [bucket.util :as util]
            [clojure.string :as string]
            [cljs.core.async :refer [put! chan]]
            [secretary.core :as secretary :refer-macros [defroute]]))

(defn process-files [files]
  "Given a list of files, processes them according to our preferences."
  (->> files
       ;; remove hidden files if specified
       (filter #(or (:show-hidden @state/global) (not (:is_hidden %))))

       ;; sort by name, case-insensitively and using alphanum
       (sort-by #(util/str->alphanum (string/lower-case (:name %))))

       ;; sort directories before "normal" files
       (sort-by #(if (:is_directory %) 0 1))

       (into [])))

(defn update-files! [path]
  "Update the global state's `:files` key to the files under `path`."
  (GET (path/join "/files/" path "/")
       {:handler #(swap! state/global assoc :files (process-files %))
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
  (.addEventListener js/window "popstate" dispatch-current-path!))
(dispatch-current-path!)
