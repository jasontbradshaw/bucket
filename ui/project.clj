(defproject bucket "0.1.0-SNAPSHOT"
  :description "A simple web file manager."
  :url "https://github.com/jasontbradshaw/bucket"

  :dependencies [[cljs-ajax "0.3.14"]
                 [binaryage/devtools "0.3.0"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.58"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.omcljs/om "0.9.0"]
                 [prismatic/om-tools "0.3.11"]
                 [sablono "0.3.5"]
                 [secretary "1.2.3"]]

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.3.1"]]

  :clean-targets ^{:protect false} [:target-path "resources/scripts/compiled"]

  :figwheel {:css-dirs ["resources/styles"]}

  :cljsbuild {
    :builds [{:id "bucket"
              :source-paths ["src"]
              :figwheel true
              :compiler {
                :main bucket.core
                :asset-path "/resources/scripts/compiled"

                :output-to "resources/scripts/compiled/main.js"
                :output-dir "resources/scripts/compiled"

                :language-out :ecmascript5-strict
                :optimizations :none
                :cache-analysis true
                :source-map true}}]})
