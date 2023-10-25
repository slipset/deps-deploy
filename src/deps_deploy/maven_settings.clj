(ns deps-deploy.maven-settings
  "Functions to work with maven settings.xml and settings-security.xml."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.apache.maven.settings.io.xpp3 SettingsXpp3Reader]
           [org.apache.maven.settings
            Server
            Settings]
           [org.sonatype.plexus.components.cipher DefaultPlexusCipher]
           [org.sonatype.plexus.components.sec.dispatcher
            DefaultSecDispatcher
            SecUtil]
           [org.sonatype.plexus.components.sec.dispatcher.model SettingsSecurity]))

(def default-settings-path (str (System/getProperty "user.home") "/.m2/settings.xml"))
(def default-settings-security-path (str (System/getProperty "user.home") "/.m2/settings-security.xml"))

;; Adapted from https://github.com/jelmerk/maven-settings-decoder/blob/master/src/main/java/org/github/jelmerk/maven/settings/Decoder.java

(defn decode-password
  "Decodes a password (got from settings.xml)."
  ^String [^String encoded-password ^String key]
  (-> (DefaultPlexusCipher.)
      (.decryptDecorated encoded-password key)))

(defn read-settings-security
  "Reads settings-security.xml file into an ^SettingsSecurity object.
   Defaults to $HOME/.m2/settings-security.xml ."
  (^SettingsSecurity []
   (read-settings-security default-settings-security-path))
  (^SettingsSecurity [settings-security-path]
   (let [settings-security-path (or settings-security-path default-settings-security-path)]
     (SecUtil/read (.getAbsolutePath (io/as-file settings-security-path)) true))))

(defn read-settings
  "Reads settings.xml file into a ^Settings object.
   Defaults to $HOME/.m2/settings.xml."
  (^Settings []
   (read-settings default-settings-path))
  (^Settings [settings-path]
   (let [settings-path (or settings-path default-settings-path)
         sr (SettingsXpp3Reader.)]
     (with-open [rdr (io/reader (io/as-file settings-path))]
       (.read sr rdr)))))

(defn decode-server-password
  "Decodes the server password, given a ^Server and plain master password."
  ^String [^String plain-master-pw ^Server server]
  (decode-password (.getPassword server) plain-master-pw))

(defn server-credentials
  "Returns a map from server id -> server settings including decoded password and ^Server instance."
  [plain-master-pw server]
  (let [id (.getId server)
        username (.getUsername server)
        decoded-pw (decode-password (.getPassword server) plain-master-pw)]
    {id {:id id
         :username username
         :password decoded-pw
         :_server server}}))

(defn servers-with-passwords
  "Decodes the passwords from servers.
   Returns a map from server id -> server settings (including credentials)."
  ([]
   (servers-with-passwords (read-settings) (read-settings-security)))
  ([^Settings settings
    ^SettingsSecurity settings-security]
   (let [encoded-master-pw (.getMaster settings-security)
         plain-master-pw (decode-password encoded-master-pw DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION)
         servers (.getServers settings)]
     (into {} (map (partial server-credentials plain-master-pw) servers)))))

(defn active-profiles
  "Map of active profile name to ^org.apache.maven.settings.Profile instance.

   opts is a map that can contain:

   :additional-profiles - collection of additional profiles to activate

   This function should follow
   https://maven.apache.org/guides/introduction/introduction-to-profiles.html#details-on-profile-activation"
  ([]
   (active-profiles (read-settings) nil))
  ([^Settings settings]
   (active-profiles settings nil))
  ([^Settings settings opts]
   (let [{:keys [additional-profiles]} opts
         active-profiles (.getActiveProfiles settings)
         active-profiles (concat active-profiles additional-profiles)
         profiles-map (.getProfilesAsMap settings)]
     (select-keys profiles-map active-profiles))))

(defn active-repositories
  "Returns a list of active repositories from settings.xml active profiles.
   Does not include crededentials."
  ([]
   (active-repositories (read-settings)))
  ([^Settings settings]
   (active-repositories settings nil))
  ([^Settings settings opts]
   (let [active-profiles (active-profiles settings opts)
         get-repos (fn get-repos [p] (into [] (.getRepositories (val p))))
         repo2props (fn repo2props [r] {(.getId r) {:id (.getId r)
                                                    :url (.getUrl r)
                                                    :name (.getName r)
                                                    :layout (.getLayout r)
                                                    :_repository r}})
         repos (mapcat get-repos active-profiles)]
     (into {} (map repo2props repos)))))

(defn deps-repositories
  "Returns a map of repo id -> repository settings for easy consumption by deps-deploy.
   Repositories are read from settings.xml.
   Passwords for each server are decoded and added to each repo."
  ([]
   (deps-repositories (read-settings) (read-settings-security)))
  ([opts]
   (deps-repositories (read-settings) (read-settings-security) opts))
  ([settings settings-security]
   (deps-repositories settings settings-security nil))
  ([settings settings-security opts]
   (let [servers-with-pw (servers-with-passwords settings settings-security)
         active-repos (active-repositories settings opts)]
     (merge-with merge servers-with-pw active-repos))))

(defn deps-repo-by-id
  "Return a map from repository id to repository settings.
   Result can be passed to deps-deploy/deploy fn:
   {repo-id (get (desp-repositories s ss) repo-id)}

   If settings and settings-security are not provided,
   the function will read $HOME/.m2/settings.xml and $HOME/.m2/settings-security.xml."
  ([^String repo-id]
   (deps-repo-by-id repo-id (read-settings) (read-settings-security) nil))
  ([^String repo-id opts]
   (deps-repo-by-id repo-id (read-settings) (read-settings-security) opts))
  ([^String repo-id ^Settings settings ^SettingsSecurity settings-security opts]
   {repo-id (get (deps-repositories settings settings-security opts) repo-id)}))

(comment

  (let [settings (read-settings (io/as-file default-settings-path))
        settings-security (read-settings-security (io/as-file default-settings-security-path))
        encoded-master-pw (.getMaster settings-security)
        plain-master-pw (decode-password encoded-master-pw DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION)
        servers (.getServers settings)]
    (doseq [s servers]
      (let [plain-pw (decode-password (.getPassword s) plain-master-pw)]
        (println (str/join (repeat 20 "-")))
        (println "Credentials for server" (.getId s) "are")
        (println "Username :" (.getUsername s))
        (println "Password :" plain-pw))))

  (servers-with-passwords)

  (read-settings default-settings-path)

  (active-profiles (read-settings) {:additional-profiles ["drevidence"]})

  (deps-repo-by-id "dre-releases")

  (deps-repositories {:additional-profiles ["drevidence"]})

  (active-repositories (read-settings))

  (deps-repositories)

  0)
