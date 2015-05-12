(ns bucket.routes
  (:require [bucket.history :as history]
            [ajax.core :refer [GET]]
            [secretary.core :as secretary :refer-macros [defroute]]))

(defn update-files! [path]
  "Update the app state's `:files` key to the files under `path`."
  (GET (str "/files/" path "/")
       {:handler #(swap! bucket.core/app-state assoc :files %)
        :format :json
        :response-format :json
        :keywords? true
        :headers {"Content-Type" "application/json"}}))

(defroute home-path #"/home(.*)" [path]
  (.log js/console "path:" path)
  (update-files! path))

(defn navigate!
  "Navigate the history to a new URL."
  ([path] (navigate! path {}))
  ([path {:keys [replace state trigger title]
          :or {replace false
               state nil
               trigger true
               title (.-title js/document)}}]
   (if replace
     (history/replace-state! state title path)
     (history/push-state! state title path))
   (if trigger
     (secretary/dispatch! path))))

(defn redirect! [path]
  "Transparently redirect the history to a new URL, leaving history alone."
  (navigate! path {:replace true :trigger true}))

;; configure and start history
(defonce history-event
  (.addEventListener js/window "popstate"
                     #(secretary/dispatch! (.. js/window -location -pathname))))

;; immediately go to the current state
(secretary/dispatch! (.. js/window -location -pathname))
