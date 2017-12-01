(ns bitwalden-node.config
  (:require [cljs.nodejs :as nodejs]
            ["mkdirp" :as mkdirp]
            ["debug/node" :as debug-fn]))

(defonce debug (debug-fn "bitwalden-node.config"))
(defonce os (nodejs/require "os"))
(defonce fs (nodejs/require "fs"))
(defonce process (nodejs/require "process"))

(defn ensure-dir [d]
  (mkdirp d
          (fn [error]
            (when error
              (print "Error creating" d)
              (print error)
              (.exit process 1))))
  d)

(defn make-filename [base-name]
  (str (.homedir os) "/.bitwalden/" base-name))

(defn make-exit-fn [atoms-to-store]
  (fn [options err]
    (if (:cleanup options) (debug "Cleaning up."))
    (when err
      (if (.-stack err)
        (js/console.error (.-stack err))
        (js/console.error err)))
    (when (:exit options) (debug "Exiting") (.exit process))
    (doseq [[filename data-atom lookup] atoms-to-store]
      (do
        (debug "Writing" filename)
        (fs.writeFileSync
          filename
          (js/JSON.stringify
            (clj->js (get-in @data-atom lookup)) nil 2)
          "utf8")))))

(defn load-to-clj [filename]
  (try (js->clj (js/JSON.parse (fs.readFileSync filename))) (catch js/Error e {})))

(defn install-exit-handler [atoms-to-store]
  (let [exit-fn (make-exit-fn atoms-to-store)]
    ; https://stackoverflow.com/a/14032965
    ; do something when app is closing
    (.on process "exit" (.bind exit-fn nil {:cleanup true}))
    ; catches ctrl+c event
    (.on process "SIGINT" (.bind exit-fn nil {:exit true}))
    ; catches uncaught exceptions
    (.on process "uncaughtException" (.bind exit-fn nil {:exit true}))))

(defn get-or-set! [configuration k default]
  (or (@configuration k) (swap! configuration assoc k default)))

