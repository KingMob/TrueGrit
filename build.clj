(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.browse :as browse]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]                 ; for b/git-count-revs
            [org.corfield.build :as bb])
  (:import (java.io File)))

(def lib 'net.modulolotus/TrueGrit)
#_(def version "0.1.0-SNAPSHOT")
;; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))

(def cljdoc-port 8000)

(defn clean [opts]
  (-> opts
      (bb/clean)))

(defn test "Run the tests." [opts]
  (-> opts
      (bb/run-tests)))

(defn jar "Make a jar file" [opts]
  (-> opts
      (assoc :lib lib :version version :src-pom "template/pom.xml")
      (bb/jar)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version :src-pom "template/pom.xml")
      (bb/run-tests)
      (bb/clean)
      (jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))


(defn- home-dir []
  (System/getProperty "user.home"))

(defn start-cljdoc-docker [opts]
  (let [docker-command-args ["docker" "run" "--rm"
                             "--publish" (str cljdoc-port ":" cljdoc-port)
                             "--volume" (str (home-dir) "/.m2:/root/.m2")
                             "--volume" "/tmp/cljdoc:/app/data"
                             "cljdoc/cljdoc"]
        {:keys [exit]} (b/process {:command-args docker-command-args})]
    (when-not (zero? exit)
      (println "ERROR: Could not start Docker with command:\n" (str/join " " docker-command-args)))))

(defn- cljdoc-docker [opts]
  (-> opts
      (assoc :lib lib :version version :src-pom "template/pom.xml")
      (bb/jar)
      (bb/install))
  (let [git-rev (b/git-process {:git-args "rev-parse HEAD"})
        cwd (-> b/*project-root* (File.) (.getCanonicalPath))
        docker-command-args [#_"echo"
                             "docker" "run" "--rm"
                             "--volume" (str cwd ":/repo-to-import")
                             "--volume" (str (home-dir) "/.m2:/root/.m2")
                             "--volume" "/tmp/cljdoc:/app/data"
                             "--entrypoint" "clojure"
                             "cljdoc/cljdoc" "-M:cli" "ingest"
                             "--project" (str lib)
                             "--version" (str version)
                             "--git" (:git opts "/repo-to-import")
                             "--rev" git-rev]
        {:keys [exit]} (b/process {:command-args docker-command-args})]
    (if (zero? exit)
      (browse/browse-url (str "http://localhost:" cljdoc-port "/d/" (str lib) "/" (str version) "/"))
      (println "ERROR: Could not run Docker with command:\n" (str/join " " docker-command-args)))))

(defn cljdoc-docker-github
  "Push to GH, then build cljdoc"
  [opts]
  (b/git-process {:git-args ["push" "origin" "main"]})
  (cljdoc-docker (merge {:git "https://github.com/KingMob/TrueGrit"} opts)))

(defn cljdoc-docker-local [opts]
  (cljdoc-docker (merge {:git "/repo-to-import"} opts)))
