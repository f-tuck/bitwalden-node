(defproject bitwalden-node "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :min-lein-version "2.5.3"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.854"]
                 [org.clojure/core.async "0.3.465"]]

  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-figwheel "0.5.14"]]
  
  :source-paths ["src"]
  :test-paths ["test"]

  :clean-targets ["target"
                  "build"]

  :figwheel {:server-port 3450}

  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :compiler {:main bitwalden-node.core
                           :output-to "target/server_dev/bitwalden_node.js"
                           :output-dir "target/server_dev"
                           :target :nodejs
                           :optimizations :none
                           :source-map true}
                :figwheel true}
               {:id "prod"
                :source-paths ["src"]
                :compiler {:output-to "build/bitwalden-server-node.js"
                           :output-dir "build"
                           :target :nodejs
                           :source-map "build/bitwalden-server-node.js.map"
                           :optimizations :simple}}
               {:id "test"
                :source-paths ["test"]
                :compiler {:main bitwalden-node.test
                           :output-to "target/test/test.js"
                           :output-dir "target/test"
                           ;:exclude "sharwode-node.core"
                           :target :nodejs
                           :source-map "target/test/test.js.map"
                           :optimizations :simple
                           :pretty-print true}}]
              :test-commands {"test-node" ["node" "target/test/test.js"]}})
