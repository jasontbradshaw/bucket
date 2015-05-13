(ns bucket.state)

;; the global state for the entire app
(defonce global (atom {
  ;; the list of files to display
  :files []

  ;; whether hidden files should be shown at all
  :show-hidden false
}))

;; log state changes for simpler debugging
(add-watch global :debug-watcher #(.log js/console "state/global:" @global))
