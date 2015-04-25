(defproject bucket "0.1.0-SNAPSHOT"
  :description "A simple web file manager."
  :url "https://github.com/jasontbradshaw/bucket"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-3211"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.omcljs/om "0.8.8"]
                 [sablono "0.3.4"]
                 [prismatic/om-tools "0.3.11"]]

  :plugins [[lein-cljsbuild "1.0.5"]]

  :source-paths ["src" "target/classes"]

  :clean-targets ["out/bucket" "out/bucket.js"]

  :cljsbuild {
    :builds [{:id "bucket"
              :source-paths ["src"]
              :compiler {
                :output-to "out/bucket.js"
                :output-dir "out"
                :language-out :ecmascript5-strict
                :optimizations :none
                :cache-analysis true
                :source-map true}}]})
