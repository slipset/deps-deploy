(ns deps-deploy.maven-settings
  "Functions to work with maven settings.xml and settings-security.xml."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.apache.maven.settings.io.xpp3 SettingsXpp3Reader]
           [org.sonatype.plexus.components.cipher
            DefaultPlexusCipher]
           [org.sonatype.plexus.components.sec.dispatcher
            DefaultSecDispatcher
            SecUtil]))

(def default-settings-path (str (System/getProperty "user.home") "/.m2/settings.xml"))
(def default-settings-security-path (str (System/getProperty "user.home") "/.m2/settings-security.xml"))

;; Adapted from https://github.com/jelmerk/maven-settings-decoder/blob/master/src/main/java/org/github/jelmerk/maven/settings/Decoder.java

(defn ^String decode-password
  "Decodes a password (got from settings.xml)."
  [^String encoded-password
   ^String key]
  (-> (DefaultPlexusCipher.)
      (.decryptDecorated encoded-password key)))

(defn ^String decode-master-password
  "Decodes master password (like the one found in settings-security.xml)."
  [^String encoded-master-password]
  (decode-password encoded-master-password DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))

(defn ^org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity read-settings-security
  "Reads settings-security.xml file into an ^org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity object.
   Defaults to $HOME/.m2/settings-security.xml ."
  ([]
   (read-settings-security nil))
  ([settings-security-path]
   (let [settings-security-path (or settings-security-path default-settings-security-path)]
     (SecUtil/read (.getAbsolutePath (io/as-file settings-security-path)) true))))

(defn ^org.apache.maven.settings.Settings read-settings
  "Reads settings.xml file into a ^org.apache.maven.settings.Settings object.
   Defaults to $HOME/.m2/settings.xml.

   See https://maven.apache.org/ref/3.6.2/maven-settings/apidocs/org/apache/maven/settings/Settings.html"
  ([]
   (read-settings nil))
  ([settings-path]
   (let [settings-path (or settings-path default-settings-path)
         sr (SettingsXpp3Reader.)]
     (with-open [rdr (io/reader (io/as-file settings-path))]
       (.read sr rdr)))))

(defn decode-server-password
  "Decodes the server passowr, given a ^org.apache.maven.settings.Server and plain master password."
  [^org.apache.maven.settings.Server server
   ^String plain-master-pw]
  (decode-password (.getPassword server) plain-master-pw))

(defn map-server-factory
  "Returns a function that can map a ^org.apache.maven.settings.Server to a map with decoded password."
  [plain-master-pw]
  (fn map-server
    [server]
    {(.getId server) {:id (.getId server)
                      :username (.getUsername server)
                      :password (decode-password (.getPassword server) plain-master-pw)
                      :_server server}}))

(defn servers-with-passwords
  "Decodes the passwords from servers.
   Returns a map from server id -> server settings (including credentials)."
  ([]
   (servers-with-passwords (read-settings) (read-settings-security)))
  ([^org.apache.maven.settings.Settings settings
    ^org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity settings-security]
   (let [encoded-master-pw (.getMaster settings-security)
         plain-master-pw (decode-master-password encoded-master-pw)
         servers (.getServers settings)
         map-server (map-server-factory plain-master-pw)]
     (into {} (map map-server servers)))))

(defn active-profiles
  "Map of active profile name to ^org.apache.maven.settings.Profile instance."
  ([]
   (active-profiles (read-settings)))
  ([^org.apache.maven.settings.Settings settings]
   (let [active-profiles (.getActiveProfiles settings)
         profiles-map (.getProfilesAsMap settings)]
     (select-keys profiles-map active-profiles))))

(defn active-repositories
  "Returns a list of active repositories from settings.xml active profiles.
   Does not include crededentials."
  ([]
   (active-repositories (read-settings)))
  ([^org.apache.maven.settings.Settings settings]
   (let [active-profiles (active-profiles settings)
         get-repos (fn get-repos [p] (into [] (.getRepositories (val p))))
         repo2props (fn repo2props [r] {(.getId r) {:id (.getId r)
                                                    :url (.getUrl r)
                                                    :name (.getName r)
                                                    :layout (.getLayout r)
                                                    :_repository r}})
         repos (flatten (map get-repos active-profiles))]
     (into {} (map repo2props repos)))))

(defn deps-repositories
  "Returns a map of repo id -> repository settings for easy consumption by deps-deploy.
   Repositories are read from settings.xml.
   Passwords for each server are decoded and added to each repo."
  ([]
   (deps-repositories nil))
  ([opts]
   (let [{:keys [settings settings-security]} opts
         settings (or settings default-settings-path)
         settings-security (or settings-security default-settings-security-path)
         settings (read-settings (io/as-file settings))
         settings-security (read-settings-security (io/as-file settings-security))
         servers-with-pw (servers-with-passwords settings settings-security)
         active-repos (active-repositories settings)]
     (merge-with merge servers-with-pw active-repos))))


(comment

  (let [settings (read-settings (io/as-file default-settings-path))
        settings-security (read-settings-security (io/as-file default-settings-security-path))
        encoded-master-pw (.getMaster settings-security)
        plain-master-pw (decode-master-password encoded-master-pw)
        servers (.getServers settings)]
    (doseq [s servers]
      (let [plain-pw (decode-password (.getPassword s) plain-master-pw)]
        (println (str/join (repeat 20 "-")))
        (println "Credentials for server" (.getId s) "are")
        (println "Username :" (.getUsername s))
        (println "Password :" plain-pw))))

  (servers-with-passwords)

  (read-settings default-settings-path)

  (active-profiles (read-settings))

  (active-repositories (read-settings))

  (deps-repositories)

  0
  )
