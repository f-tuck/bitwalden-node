(defproject sharewode-node "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :min-lein-version "2.5.3"

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]]

  :plugins [[lein-cljsbuild "1.1.1"]
            [lein-figwheel "0.5.0-2"]]
  
  :source-paths ["src"]
  :test-paths ["test"]

  :clean-targets ["server.js"
                  "target"
                  "build"]

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :figwheel true
                :compiler {:main sharewode-node.core
                           :output-to "target/server_dev/sharewode_node.js"
                           :output-dir "target/server_dev"
                           :target :nodejs
                           :optimizations :none
                           :source-map true}}
               {:id "prod"
                :source-paths ["src"]
                :compiler {:output-to "build/sharewode-server-node.js"
                           :output-dir "build"
                           :target :nodejs
                           :source-map "build/sharewode-server-node.js.map"
                           :optimizations :simple}}
               {:id "test"
                :source-paths ["test"]
                :compiler {:main sharewode-node.test
                           :output-to "target/test/test.js"
                           :output-dir "target/test"
                           ;:exclude "sharwode-node.core"
                           :target :nodejs
                           :source-map "target/test/test.js.map"
                           :optimizations :simple
                           :pretty-print true}}]
              :test-commands {"test-node" ["node" "target/test/test.js"]}})
