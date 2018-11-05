(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.java.io :as io])
  (:import [org.bouncycastle.bcpg SymmetricKeyAlgorithmTags]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.openpgp.examples ByteArrayHandler]
           [java.security NoSuchProviderException Security]
           [java.io File]))

(defn read-byte-array [file]
  (with-open [in (io/input-stream (io/file file))]
    (let [buf (byte-array 1000)
          n (.read in buf)]
      buf)))

(defn decrypt [file]
  (Security/addProvider (BouncyCastleProvider.))
  (let [encrypted-byte-array (read-byte-array file)
        console (System/console)
        passwd (.readPassword console "Please enter your gpg passphrase: " (to-array [(Object.)]))
        decrypted (ByteArrayHandler/decrypt encrypted-byte-array passwd)]
    (edn/read-string (String. decrypted))))

(def clojars-gpg-file ".clojars_creds.edn.gpg")

(defn clojars-repo-settings []
  (let [settings {:url "https://clojars.org/repo"}]
    {"clojars" (merge settings (if (.exists (File. clojars-gpg-file))
                                 (decrypt clojars-gpg-file)
                                 {:username (System/getenv "CLOJARS_USERNAME")
                                  :password (System/getenv "CLOJARS_PASSWORD")}))}))

(defmulti deploy :installer)

(defmethod deploy :clojars [{:keys [artifact name version repository]
                             :or {repository (clojars-repo-settings)} :as opts }]
  (println "Deploying"  (str name "-" version) "to clojars as" (-> repository vals first :username))
  (aether/deploy :pom-file "pom.xml"
                 :jar-file artifact
                 :repository repository
                 :coordinates [(symbol name) version])
  (println "done."))

(defmethod deploy :local [{:keys [artifact name version] :as opts}]
  (println "Installing" (str name "-" version)  "to your local `.m2`")
  (aether/install :jar-file (str artifact)
                  :pom-file "pom.xml"
                  :transfer-listener :stdout
                  :coordinates [name version])
  (println "done."))

(defn -main [deploy-or-install artifact name version]
  (let [artifact-info {:installer (cond (= "deploy" deploy-or-install) :clojars
                                        (= "install" deploy-or-install) :local)
                       :artifact artifact
                       :name (symbol name)
                       :version version}]
    (deploy artifact-info)))
