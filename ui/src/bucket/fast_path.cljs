(ns bucket.fast-path
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [ajax.core :refer [GET]]
            [bucket.path :as path]
            [bucket.state :as state]
            [clojure.string :as string]
            [cljs.core.async :refer [put! chan <! timeout]]))

;; where request state is stored
(def requests (atom {}))

;; we serialize all actions into a single queue to make reasoning simple
(def queue (chan 20))

(defn- request-expired? [req]
  "Returns whether a request is too old."
  (let [age-ms (- (.getTime (js/Date.)) (:created-at req))]
    (> age-ms 10000)))

(defn- prune-requests! []
  "Remove every existing request older than 10 seconds."
  (swap! requests
         (fn [s]
           (apply dissoc s
                  (->> s
                       (filter #(request-expired? (get % 1)))
                       (map first))))))


(defn- default-request [req path]
  "Intentionally puts the request on top of any existing request so as not to
   modify the existing one if it's already present."
  (merge
    {:path path
     :created-at (.getTime (js/Date.))
     :pending true
     :confirmed false
     :files nil}
    req))

(defn- complete-request [req path files]
  "Marks the given request as completed and sets its files."
  (merge (default-request req path)
         {:pending false
          :files files}))

(defn- confirm-request [req path]
  "Marks the given request as confirmed."
  (merge (default-request req path)
         {:confirmed true}))

(defn- process-files [files]
  "Given a list of files, processes them according to our preferences."
  (let [show-hidden (:show-hidden @state/global)]
    (filterv #(or show-hidden (not (:is_hidden %))) files)))

(defn- update-path! [p files]
  "Update the global state's `:files` key to the files under `p` and set `:path`
   to the segments of the given path."
  (swap! state/global
         (fn [s]
           ;; update the path and the files for the path
           (assoc s
                  :files (process-files files)
                  :path (path/segmentize (js/decodeURIComponent p))))))

(defmulti queue-worker :type)

(defmethod queue-worker :notification [{:keys [path]} korks]
  "Kick off an AJAX request for the files at the given path."
  (swap! requests
         (fn [s]
           ;; schedule a request for the path if one hasn't been started yet
           (if (not (contains? s path))
             (put! queue {:type :request, :path path}))

           ;; mark this request as pending if it doesn't exist
           (assoc s path (default-request (get s path) path)))))

(defmethod queue-worker :request [{:keys [path]} korks]
  "Make an AJAX request for the files at the given path."
  (GET (path/join "/files/" path "/")
       {:handler #(put! queue {:type :completion
                               :path path
                               :files %})
        :format :json
        :response-format :json
        :keywords? true
        :headers {"Content-Type" "application/json"}}))

(defmethod queue-worker :completion [{:keys [path files]} korks]
  "Mark a pending request as completed using the given files."
  (swap! requests
         (fn [s]
           (let [req (get s path)]
             ;; if the request is already confirmed, schedule a navigation
             (if (:confirmed req)
               (put! queue {:type :navigation, :path path}))

             ;; mark the request as confirmed
             (assoc s path (complete-request req path files))))))

(defmethod queue-worker :confirmation [{:keys [path]} korks]
  "Confirm that we truly want to navigate to the given path."
  (swap! requests
         (fn [s]
           ;; schedule a navigation then mark the request as confirmed
           (put! queue {:type :navigation, :path path})
           (assoc s path (confirm-request (get s path) path)))))

(defmethod queue-worker :navigation [{:keys [path]} korks]
  "Navigate to the given path."
  (swap! requests
         (fn [s]
           ;; if the request exists, isn't pending, and is confirmed, navigate!
           ;; otherwise, leave the state alone.
           (let [req (get s path)]
             (when (and req
                      (not (:pending req))
                      (:confirmed req))
                 (.log js/console "Time taken:"
                       (- (.getTime (js/Date.)) (:created-at req)))
                 (update-path! path (:files (get s path)))
                 (dissoc s path))
               s))))

(defonce _
  (do
    ;; process messages
    (go-loop []
             (let [work (<! queue)]
               (queue-worker work)
               (recur)))

    ;; clean up expired requests
    (go-loop []
             (<! (timeout 3000))
             (prune-requests!)
             (recur))))

(defn notify! [path]
  "Notify that the data for a path should be fetched. Must be followed by a
   `confirm` call in order to actually change the path!"
  (put! queue {:type :notification
               :path (string/replace path #"^/home/" "/")}))

(defn confirm! [path]
  "Confirm that a path was selected and should be changed to."
  (put! queue {:type :confirmation
               :path (string/replace path #"^/home/" "/")}))
