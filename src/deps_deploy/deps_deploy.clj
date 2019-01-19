(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [deps-deploy.gpg :as gpg]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]))

(def default-repo-settings {"clojars" {:url "https://clojars.org/repo"
                                       :username (System/getenv "CLOJARS_USERNAME")
                                       :password (System/getenv "CLOJARS_PASSWORD")}})

(def artifact-id-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/artifactId)
(def group-id-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/groupId)
(def version-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/version)

;; copied directly from leiningen
(defn- extension [f]
  (if-let [[_ signed-extension] (re-find #"\.([a-z]+\.asc)$" f)]
    signed-extension
    (if (= "pom.xml" (.getName (io/file f)))
      "pom"
      (last (.split f "\\.")))))

(defn classifier
  "The classifier is be located between the version and extension name of the artifact.
  See http://maven.apache.org/plugins/maven-deploy-plugin/examples/deploying-with-classifiers.html "
  [version f]
  (let [pattern (re-pattern (format "%s-(\\p{Alnum}*)\\.%s" version (extension f)))
        [_ classifier-of] (re-find pattern f)]
    (when (seq classifier-of)
      classifier-of)))
;; copy stops here

(defn coordinates-from-pom [pom-str]
  (let [tmp (->> pom-str
                 xml/parse-str
                 :content
                 (remove string?)
                 (keep (fn [{:keys [tag] :as m}]
                         (when (or (= tag
                                      artifact-id-tag)
                                   (= tag
                                      group-id-tag)
                                   (= tag
                                      version-tag))
                           {(keyword (name tag)) (first (:content m))})))
                 (apply merge))]
    {:coordinates [(symbol (str (:groupId tmp) "/" (:artifactId tmp))) (:version tmp)]}))

(defn sign! [pom jar-file]
  (let [passphrase (gpg/read-passphrase)]
    [(gpg/sign! passphrase pom) (gpg/sign! passphrase jar-file)]))

(defn artifacts [version files]
  (into {} (for [f files]
             [[:extension (extension f)
               :classifier (classifier version f)] f])))

(defn all-artifacts [sign? version artifiact]
  (let [files ["pom.xml" artifact]
        signature-files (when sign? (sign! "pom.xml" artifact))
        all-files (into files signature-files)]
    (artifacts version all-files)))

(defmulti deploy :installer)

(defmethod deploy :clojars [{:keys [artifact-map coordinates repository]
                             :or {repository default-repo-settings} :as opts }]
  (println "Deploying" (str (first coordinates) "-" (second coordinates)) "to clojars as"
           (-> repository vals first :username))
  (aether/deploy {:artifact-map artifact-map
                  :repository repository
                  :coordinates coordinates}))

(defmethod deploy :local [{:keys [artifact-map coordinates]}]
  (println "Installing" (str (first coordinates) "-" (second coordinates)) "to your local `.m2`")
  (aether/install :artifact-map artifacts-map
                  :transfer-listener :stdout
                  :coordinates coordinates)
  (println "done."))

(defn -main [deploy-or-install artifact & [sign-releases]]
  (let [coordinates (coordinates-from-pom (slurp "pom.xml"))]
    (->> {:installer (cond (= "deploy" deploy-or-install) :clojars
                           (= "install" deploy-or-install) :local)
          :artifact-map (all-artifacts sign-releases (second (:coordinates coordinates)) artifact)}
         (merge coordinates)
         deploy)))
