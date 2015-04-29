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
             (.log js/console (clj->js @app))))

;; the root element of our application
(defonce root (.querySelector js/document "main"))

(def ^:const id-alphabet "ABCDEFGHJKMNPQRSTVWXYZ0123456789")
(defn generate-id
  "Generates and returns a new Crockford-encoded random string of the given
  length (default 16 characters)."
  ([] (generate-id 16))
  ([length] (apply str (repeatedly length #(rand-nth id-alphabet)))))

;; a single file or folder
(defcomponent file [component owner]
  (render-state [this {:keys [delete]}]
                (html [:div "file"])))

(defcomponent file-list [app owner]
  (init-state [_]
    {:create (chan)
     :delete (chan)})

  (will-mount [_]
    ;; listen for events that modify the files
    (let [create (om/get-state owner :create)
          delete (om/get-state owner :delete)]
      ;; create
      (go (loop []
            (let [component (<! create)]
              (om/transact! app :components
                            (fn [components]
                              ;; add the new component onto the list
                              (conj components component))
                            :create)
              (recur))))
      ;; delete
      (go (loop []
            (let [component (<! delete)]
              (om/transact! app :components
                            (fn [components]
                              (vec (remove #(= component %) components)))
                            :delete)
              (recur))))))

  (render-state [this {:keys [create delete]}]
                (html [:div {:class "file-list"}
                       (om/build-all file (:files app)
                                     {:init-state {:delete delete}})])))

(om/root file-list app {:target root})
