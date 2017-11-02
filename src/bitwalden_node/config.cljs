(ns bitwalden-node.config
  (:require [cljs.nodejs :as nodejs]))

(defonce debug ((nodejs/require "debug") "bitwalden-node.config"))
(defonce os (nodejs/require "os"))
(defonce fs (nodejs/require "fs"))
(defonce mkdirp (nodejs/require "mkdirp"))
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
    (if err (and (.-stack err)) (.log js/console (.-stack err)))
    (when (:exit options) (debug "Exiting") (.exit js/process))
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
    (.on js/process "exit" (.bind exit-fn nil {:cleanup true}))
    ; catches ctrl+c event
    (.on js/process "SIGINT" (.bind exit-fn nil {:exit true}))
    ; catches uncaught exceptions
    (.on js/process "uncaughtException" (.bind exit-fn nil {:exit true}))))

(defn get-or-set! [configuration k default]
  (or (@configuration k) (swap! configuration assoc k default)))

