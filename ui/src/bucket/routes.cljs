(ns bucket.routes
  (:require [bucket.history :as history]
            [bucket.path :as path]
            [bucket.fast-path :as fast-path]
            [secretary.core :as secretary :refer-macros [defroute]]))

(defroute home-path #"/home(.*)" [path]
  ;; immediately notify that we want to change to this path. if someone else
  ;; has already notified, this does nothing. then, immediately confirm that we
  ;; want to change the path so it will actually be changed.
  (fast-path/notify! path)
  (fast-path/confirm! path))

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
