(ns bucket.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [ajax.core :refer [GET POST]]
            [figwheel.client :as figwheel]
            [clojure.string :as string]
            [cljs.core.async :refer [put! chan <!]]
            [devtools.core :as devtools]
            [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]))

;; TODO: put these in a module that only gets loaded during dev mode
(enable-console-print!)
(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})
(devtools/install!)

(defonce app-state (atom {
  ;; the list of files to display
  :files []
}))

;; show app state changes for simpler debugging
(add-watch app-state :debug-watcher
           (fn [_ _ _ _]
             (.log js/console "State:" app-state)))

;; the root element of our application
(defonce root (.querySelector js/document "main"))

(GET "/files/"
     {:handler #(swap! app-state assoc :files %)
      :format :json
      :response-format :json
      :keywords? true
      :headers {"Content-Type" "application/json"}})

(def ^:const id-alphabet "ABCDEFGHJKMNPQRSTVWXYZ0123456789")
(defn generate-id
  "Generates and returns a new Crockford-encoded random string of the given
  length (default 16 characters)."
  ([] (generate-id 16))
  ([length] (apply str (repeatedly length #(rand-nth id-alphabet)))))

;; a single file
(defcomponent file [file owner]
  (render [this]
          (html [:div {:class "file"} (:name file)])))

;; a list of files
(defcomponent file-list [app-state owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file (:files app-state))])))

(om/root file-list app-state {:target root})
