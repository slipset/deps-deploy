(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [deps-deploy.gpg :as gpg]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]))


(aether/register-wagon-factory! "s3p" #(org.springframework.build.aws.maven.PrivateS3Wagon.))
(aether/register-wagon-factory! "s3" #(org.springframework.build.aws.maven.SimpleStorageServiceWagon.))


(def default-repo-settings {"clojars" {:url (or (System/getenv "CLOJARS_URL") "https://clojars.org/repo")
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
    {:group-id (:groupId tmp)
     :artifact-id (:artifactId tmp)
     :version (:version tmp)}))

(defn- versioned-pom-filename [{:keys [version artifact-id]}]
  (str artifact-id "-" version ".pom"))

(defn mvn-coordinates [{:keys [group-id artifact-id version]}]
  [(symbol (str group-id "/" artifact-id)) version])

(defn sign! [pom jar-file]
  (let [passphrase (gpg/read-passphrase)]
    [(gpg/sign! passphrase pom) (gpg/sign! passphrase jar-file)]))

(defn artifacts [version files]
  (into {} (for [f files]
             [[:extension (extension f)
               :classifier (classifier version f)] f])))

(defn all-artifacts [sign? {:keys [version] :as coordinates} artifact]
  (let [pom (versioned-pom-filename coordinates)
        files [pom  artifact]
        signature-files (when sign? (sign! pom artifact))
        all-files (into files signature-files)]
    (artifacts version all-files)))

(defn- artifact [{:keys [group-id artifact-id version]}]
  (str group-id "/" artifact-id "-" version))

(defmulti deploy* :installer)

(defmethod deploy* :remote [{:keys [artifact-map coordinates repository]
                             :or {repository default-repo-settings} :as opts}]
  (println "Deploying" (artifact coordinates) "to clojars as"
           (-> repository vals first :username))
  (java.lang.System/setProperty "aether.checksums.forSignature" "true")
  (aether/deploy :artifact-map artifact-map
                 :repository repository
                 :transfer-listener :stdout
                 :coordinates (mvn-coordinates coordinates))
  (println "done."))

(defmethod deploy* :local [{:keys [artifact-map coordinates]}]
  (println "Installing" (artifact coordinates) "to your local `.m2`")
  (aether/install :artifact-map artifact-map
                  :transfer-listener :stdout
                  :coordinates (mvn-coordinates coordinates))
  (println "done."))

(defn- tag-val-replacer [tag->val]
  (fn [{:keys [tag content attrs] :as el}]
    (let [replacement-val (tag->val tag ::default)]
      (if (= ::default replacement-val)
        el
        (assoc el :content replacement-val)))))

(defn update-pom [{:keys [pom-file
                          mvn/version
                          mvn/group-id
                          mvn/artifact-id] :as opts}]
  (-> (or pom-file "pom.xml")
      io/reader
      xml/parse
      (update :content
                 #(map (tag-val-replacer {version-tag [version]
                                          group-id-tag [group-id]
                                          artifact-id-tag [artifact-id]}) %))
      xml/emit-str))

(defn deploy
  "The main entry point into deps-deploy via tools.deps :exec-fn which
  supports an opts map that can be supplied via :exec-args.

  Required keys are:

  :artifact   A string specifying the file path relative to the current
              working directory to the artifact to be deployed.  This will
              normally be your library packaged as a jar.

  :installer  Set to either :local or :remote depending on whether you
              want to install into your local .m2 cache or to a remote
              :repository.

  :pom-file   defaults to \"pom.xml\"

  :sign-releases?  A boolean that specifies whether releases should be
                   signed

  "
  [{:keys [sign-releases? artifact] :as opts}]
  (let [updated-pom (update-pom opts)
        coordinates (coordinates-from-pom updated-pom)
        updated-pom-file-name (versioned-pom-filename coordinates)]
    (spit updated-pom-file-name updated-pom)
    (try
      (deploy*
       (assoc opts
              :artifact-map (all-artifacts sign-releases? coordinates artifact)
              :coordinates coordinates))
      (finally
        (.delete (java.io.File. updated-pom-file-name))))))

;; command line mode
(defn -main [deploy-or-install artifact & [sign-releases]]
  (deploy {:installer (cond (= "deploy" deploy-or-install) :remote
                            (= "install" deploy-or-install) :local)
           :sign-releases? (= "true" sign-releases)
           :artifact artifact}))

(comment

  (deploy {:installer :remote
           :artifact "deps-deploy.jar"
           :pom-file "pom.xml"
           :mvn/version "999"
           :repository {"clojars" {:url "https://clojars.org/repo"
                                   :username "clojars-username"
                                   :password "CLOJARS-token"}}})

  )
