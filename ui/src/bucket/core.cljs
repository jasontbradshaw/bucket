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

(defonce app (atom {
  ;; the list of files/folders to display
  :files []
}))

;; FIXME: remove this!
(add-watch app :debug-watcher
           (fn [_ _ _ _]
             (.log js/console app)))

;; the root element of our application
(defonce root (.querySelector js/document "main"))

(GET "/files/"
     {:handler #(om/update! (om/root-cursor app) :files %)
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

;; a single file or folder
(defcomponent file [file owner]
  (render [this]
          (html [:div {:class "file"} (:name file)])))

(defcomponent file-list [files owner]
  (render [this]
          (html [:div {:class "file-list"}
                 (om/build-all file files)])))

(om/root file-list (:files app) {:target root})
