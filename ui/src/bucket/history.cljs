(ns bucket.history
  "A lightweight wrapper around js/history")

(defn- current-state []
  "The raw value of the current history state."
  (.-state js/history))

(defn current-title []
  "The current document title."
  (.-title js/document))

(defn current-path []
  "The current path of the window excluding hostname and including leading `/`."
  (.. js/window -location -pathname))

(defn back! [] (.back js/history))
(defn forward! [] (.forward js/history))
(defn go! [idx] (.go js/history idx))

(defn replace-state!
  ([state]
     (replace-state! state (current-title) (current-path)))
  ([state title]
     (replace-state! state title (current-path)))
  ([state title path]
     (.replaceState js/history state title path)))

(defn push-state!
  ([state]
     (push-state! state (current-title) (current-path)))
  ([state title]
     (push-state! state title (current-path)))
  ([state title path]
     (.pushState js/history state title path)))

(defn navigate!
  "Navigate the window to a new path."
  ([path] (navigate! path {}))
  ([path {:keys [replace state title]
          :or {replace false
               state nil
               title (.-title js/document)}}]
   (if replace
     (replace-state! state title path)
     (push-state! state title path))))

;; an atom that encapsulates the current history state
(def state
  (reify
    IDeref
    (-deref [_]
      (current-state))
    IReset
    (-reset! [_ v]
      (replace-state! v))
    ISwap
    (-swap! [this f]
      (-reset! this (f (current-state))))
    (-swap! [this f x]
      (-reset! this (f (current-state) x)))
    (-swap! [this f x y]
      (-reset! this (f (current-state) x y)))
    (-swap! [this f x y more]
      (-reset! this (apply f (current-state) x y more)))))
