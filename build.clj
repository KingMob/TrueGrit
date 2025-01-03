;; Run with clj-T:build X

(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.browse :as browse]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]                 ; for b/git-count-revs
            [org.corfield.build :as bb])
  (:import (java.io File)))

(def lib 'net.modulolotus/truegrit)

;; Version is MAJOR.MINOR.COMMITS:
(def version (format "2.3.%s" (b/git-count-revs nil)))
(def extra-build-opts {:lib     lib
                       :version version
                       :tag     version
                       :src-pom "template/pom.xml"})
(def deployment-branch "main")

(def cljdoc-port 8000)

(defn rev-count [_]
  (println "Current revision count:" (b/git-count-revs nil)))

(defn clean [opts]
  (-> opts
      (bb/clean)))

(defn test "Run the tests." [opts]
  (-> opts
      (bb/run-tests)))

(defn jar "Make a jar file" [opts]
  (-> opts
      (merge extra-build-opts)
      (bb/jar)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (merge extra-build-opts)
      (bb/run-tests)
      (bb/clean)
      (jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (merge extra-build-opts)
      (bb/install)))

(defn tag "Tag commit with current version" [_]
  (b/git-process {:git-args (str "tag " version)}))

(defn current-version [_]
  (println version))

(defn- current-branch []
  (b/git-process {:git-args "branch --show-current"}))

(defn push-branch "Push current branch to origin." [_]
  (b/git-process {:git-args ["push" "origin" (current-branch)]}))

(defn push-tags "Push all tags to origin" [_]
  (b/git-process {:git-args ["push" "--tags"]}))

(defn deploy
  "Tag with the current version, push to GH (for cljdoc), and
   deploy the jar to Clojars."
  [opts]
  (if (= (current-branch) deployment-branch)
    (do
      (tag opts)
      (push-branch opts)
      (push-tags opts)
      (-> opts
          (merge extra-build-opts)
          (bb/deploy)))
    (throw (ex-info (str "Current branch is not " deployment-branch ". Cannot deploy.")
                    {:current-branch (current-branch)}))))


(defn- home-dir []
  (System/getProperty "user.home"))

;; 1. Run this, then leave open
(defn start-cljdoc-docker [opts]
  (let [docker-command-args ["docker" "run" "--rm"
                             "--publish" (str cljdoc-port ":" cljdoc-port)
                             "--volume" (str (home-dir) "/.m2:/root/.m2")
                             "--volume" "/tmp/cljdoc:/app/data"
                             "cljdoc/cljdoc"]
        {:keys [exit]} (b/process {:command-args docker-command-args})]
    (when-not (zero? exit)
      (println "ERROR: Could not start Docker with command:\n" (str/join " " docker-command-args)))))

(defn- cljdoc-docker
  "Do not call directly."
  [opts]
  (-> opts
      (merge extra-build-opts)
      (bb/jar)
      (bb/install))
  (let [git-rev (b/git-process {:git-args "rev-parse HEAD"})
        cwd (-> ^String b/*project-root* (File.) (.getCanonicalPath))
        docker-command-args [#_"echo"
                             "docker" "run" "--rm"
                             "--volume" (str cwd ":/repo-to-import")
                             "--volume" (str (home-dir) "/.m2:/root/.m2")
                             "--volume" "/tmp/cljdoc:/app/data"
                             "--entrypoint" "clojure"
                             "cljdoc/cljdoc" "-Sforce" "-A:cli" "ingest"
                             "--project" (str lib)
                             "--version" (str version)
                             "--git" (:git opts "/repo-to-import")
                             "--rev" git-rev]
        {:keys [exit]} (b/process {:command-args docker-command-args})]
    (if (zero? exit)
      (browse/browse-url (str "http://localhost:" cljdoc-port "/d/" (str lib) "/" (str version) "/"))
      (println "ERROR: Could not run Docker with command:\n" (str/join " " docker-command-args)))))

;; 2a. Run this to see GH assets
(defn cljdoc-docker-github
  "Push to GH, then build cljdoc"
  [opts]
  (push-branch opts)
  (cljdoc-docker (merge {:git "https://github.com/KingMob/TrueGrit"} opts)))

;; 2b. ..or run this if you don't care about GH links
(defn cljdoc-docker-local [opts]
  (cljdoc-docker (merge {:git "/repo-to-import"} opts)))

