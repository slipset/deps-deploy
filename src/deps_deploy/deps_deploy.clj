(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [deps-deploy.gpg :as gpg]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.tools.deps :as t]
            [clojure.tools.deps.util.dir :as dir])
  (:import [org.springframework.build.aws.maven
            PrivateS3Wagon SimpleStorageServiceWagon]
            ;; maven-core
           [org.apache.maven.settings DefaultMavenSettingsBuilder Settings Server]
            ;; maven-settings-builder
           [org.apache.maven.settings.building DefaultSettingsBuilderFactory]))

(aether/register-wagon-factory! "s3p" #(PrivateS3Wagon.))
(aether/register-wagon-factory! "s3" #(SimpleStorageServiceWagon.))

(def default-repo-settings {:id "clojars"
                            :url (or (System/getenv "CLOJARS_URL") "https://clojars.org/repo")
                            :username (System/getenv "CLOJARS_USERNAME")
                            :password (System/getenv "CLOJARS_PASSWORD")})

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

(defn sign! [pom jar-file sign-key-id]
  (let [passphrase (gpg/read-passphrase)
        sign-op! (if sign-key-id
                   (partial gpg/sign-with-key! sign-key-id)
                   (partial gpg/sign! passphrase))]
    [(sign-op! pom) (sign-op! jar-file)]))

(defn artifacts [version files]
  (into {} (for [f files]
             [[:extension (extension f)
               :classifier (classifier version f)] f])))

(defn all-artifacts [sign? {:keys [version] :as coordinates} artifact sign-key-id]
  (let [pom (versioned-pom-filename coordinates)
        files [pom  artifact]
        signature-files (when sign? (sign! pom artifact sign-key-id))
        all-files (into files signature-files)]
    (artifacts version all-files)))

(defn- artifact [{:keys [group-id artifact-id version]}]
  (str group-id "/" artifact-id "-" version))

(defn- assoc-some
  "Associates a key k, with a value v in a map m, if and only if v is not nil.
   Copied from https://github.com/weavejester/medley"
  ([m k v]
   (if (nil? v) m (assoc m k v)))
  ([m k v & kvs]
   (reduce (fn [m [k v]] (assoc-some m k v))
           (assoc-some m k v)
           (partition 2 kvs))))

(defn- read-edn-files
  "Given as options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files, from depstar"
  [{:keys [repro] :or {repro true}}]
  (let [{:keys [root-edn user-edn project-edn]} (t/find-edn-maps)]
    (t/merge-edns (if repro
                    [root-edn project-edn]
                    [root-edn user-edn project-edn]))))

(defn- set-settings-builder
  "Copied from clojure.tools.deps.alpha"
  [^DefaultMavenSettingsBuilder default-builder settings-builder]
  (doto (.. default-builder getClass (getDeclaredField "settingsBuilder"))
    (.setAccessible true)
    (.set default-builder settings-builder)))

(defn- get-settings
  "Copied from clojure.tools.deps.alpha"
  ^Settings []
  (.buildSettings
   (doto (DefaultMavenSettingsBuilder.)
     (set-settings-builder (.newInstance (DefaultSettingsBuilderFactory.))))))

(defn- get-repo-settings
  [repo mvn-repos]
  (when (contains? mvn-repos repo)
    (let [repo-settings (->> (get-settings)
                             (.getServers)
                             (filter #(= repo (.getId ^Server %)))
                             first)]
      (assoc-some (get mvn-repos repo)
                  :username (.getUsername repo-settings)
                  :password (.getPassword repo-settings)))))

(defn- preprocess-options
  "Given an options hash map, if any of the values are keywords, look them
  up as alias values from the full basis (including user `deps.edn`).
  :installer is the only option that is expected to have a keyword value
  so we skip the lookup for that.
  Typically the value of the :repository option is a hashmap, if it is string,
  that string is used as the key to get the repoistory hashmap from the :mvn/repos hashmap from the full basis.
  Code to read ~/.m2/settings.xml and get auth credentials from clojure.tools.deps.alpha
  Based on same fn in depstar"
  [options]
  (let [edn-files (read-edn-files {:repro false})
        aliases   (:aliases edn-files)
        mvn-repos (:mvn/repos edn-files)]
    (reduce-kv (fn [opts k v]
                 (cond
                   (and (not= :installer k) (keyword? v))
                   (if (contains? aliases v)
                     (assoc opts k (get aliases v))
                     (do
                       (println k "has value" v "which is an unknown alias")
                       opts))
                   (and (= :repository k) (string? v))
                   (if-let [repo-map (get-repo-settings v mvn-repos)]
                     (assoc opts k {v repo-map})
                     (do
                       (println k "has value" v "which is an unknown :mvn/repos")
                       opts))
                   :else opts))
               options
               options)))

(defmulti deploy* :installer)

(defn- print-deploy-message [{:keys [repository coordinates]}]
  ;; NOTE: pomegranate seems to assume the map contains only a single
  ;; repository-id/settings pair.
  (let [id (-> repository keys first)
        settings (-> repository vals first)
        {:keys [username]} settings]
    (if username
      (println "Deploying" (artifact coordinates) "to repository" id "as" username)
      (println "Deploying" (artifact coordinates) "to repository" id "."))))

(defmethod deploy* :remote [{:keys [artifact-map coordinates repository] :as opts}]

  (let [opts (assoc opts :repository repository)]
    (print-deploy-message opts)
    (java.lang.System/setProperty "aether.checksums.forSignature" "true")
    (java.lang.System/setProperty "aether.checksums.omitChecksumsForExtensions" "")
    (aether/deploy :artifact-map artifact-map
                   :repository repository
                   :transfer-listener :stdout
                   :coordinates (mvn-coordinates coordinates))
    (println "done.")))

(defmethod deploy* :local [{:keys [artifact-map coordinates]}]
  (println "Installing" (artifact coordinates) "to your local `.m2`")
  (aether/install :artifact-map artifact-map
                  :transfer-listener :stdout
                  :coordinates (mvn-coordinates coordinates))
  (println "done."))

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
  [options]
  (let [{:keys [pom-file sign-releases? sign-key-id artifact] :as opts} (preprocess-options options)
        pom (slurp (dir/canonicalize (io/file (or pom-file "pom.xml"))))
        coordinates (coordinates-from-pom pom)
        artifact (str artifact)
        [exec-args-repo-id exec-args-repo-settings] (->> opts :repository (into []) first)
        repository {(or exec-args-repo-id
                        (:id default-repo-settings))
                    {:url (or (:url exec-args-repo-settings)
                              (:url default-repo-settings))
                     :username (or (:username exec-args-repo-settings)
                                   (:username default-repo-settings))
                     :password (or (:password exec-args-repo-settings)
                                   (:password default-repo-settings))}}]
    (spit (versioned-pom-filename coordinates) pom)

    (try
      (deploy*
       (assoc opts
              :artifact-map (all-artifacts sign-releases? coordinates artifact sign-key-id)
              :coordinates  coordinates
              :repository   repository))
      (finally
        (.delete (java.io.File. (versioned-pom-filename coordinates)))))))

;; command line mode
(defn -main [deploy-or-install artifact & [sign-releases sign-key-id]]
  (deploy {:installer (cond (= "deploy" deploy-or-install) :remote
                            (= "install" deploy-or-install) :local)
           :sign-releases? (= "true" sign-releases)
           :sign-key-id sign-key-id
           :artifact artifact}))
