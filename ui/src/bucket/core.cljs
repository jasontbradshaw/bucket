(ns bucket.core
  (:require [bucket.components.app :refer [app]]
            [bucket.state :as state]
            [devtools.core :as devtools]
            [om-tools.core :refer-macros [defcomponent]]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

;; turn console.log into the default print function
(enable-console-print!)

;; TODO: put this in a module that only gets loaded during dev mode
(devtools/install!)

;; start the app
(defonce root (.querySelector js/document "body"))
(om/root app state/global {:target root})
