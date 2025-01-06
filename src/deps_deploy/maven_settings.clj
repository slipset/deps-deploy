(ns deps-deploy.maven-settings
  "Functions to work with maven settings.xml and settings-security.xml."
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
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

(defn encoded-pw?
  [s]
  (and (string/starts-with? s "{")
       (string/ends-with? s "}")))

;; Adapted from https://github.com/jelmerk/maven-settings-decoder/blob/master/src/main/java/org/github/jelmerk/maven/settings/Decoder.java

(defn decode-password
  "Decodes a password (got from settings.xml)."
  ^String
  ([^String encoded-password]
   (decode-password encoded-password DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))
  ([^String encoded-password ^String key]
   (-> (DefaultPlexusCipher.)
       (.decryptDecorated encoded-password key))))

(defn read-settings-security
  "Reads settings-security.xml file into an ^SettingsSecurity object.
   Defaults to $HOME/.m2/settings-security.xml ."
  (^SettingsSecurity []
   (read-settings-security default-settings-security-path))
  (^SettingsSecurity [settings-security-path]
   (let [settings-security-path (or settings-security-path default-settings-security-path)
         f (io/as-file settings-security-path)]
     (when (and (some? f) (.exists f))
       (SecUtil/read (.getAbsolutePath f) true)))))

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
        decoded-pw (if (and (some? plain-master-pw)
                            (encoded-pw? (.getPassword server)))
                     (decode-password (.getPassword server) plain-master-pw)
                     (.getPassword server))]
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
   (let [plain-master-pw (when settings-security
                           (decode-password (.getMaster settings-security)
                                            DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))
         servers (.getServers settings)]
     (into {} (map (partial server-credentials plain-master-pw) servers)))))

(defn active-profiles
  "Map of active profile name to ^org.apache.maven.settings.Profile instance."
  ([]
   (active-profiles (read-settings)))
  ([^Settings settings]
   (let [active-profiles (.getActiveProfiles settings)
         profiles-map (.getProfilesAsMap settings)]
     (select-keys profiles-map active-profiles))))

(defn active-repositories
  "Returns a list of active repositories from settings.xml active profiles.
   Does not include crededentials."
  ([]
   (active-repositories (read-settings)))
  ([^Settings settings]
   (let [active-profiles (active-profiles settings)
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
  ([settings settings-security]
   (let [servers-with-pw (servers-with-passwords settings settings-security)
         active-repos (active-repositories settings)]
     (merge-with merge servers-with-pw active-repos))))

(defn deps-repo-by-id
  "Return a map from repository id to repository settings.
   Result can be passed to deps-deploy/deploy fn:
   {repo-id (get (desp-repositories s ss) repo-id)}

   If not provided, will read $HOME/.m2/settings.xml and $HOME/.m2/settings-security.xml."
  ([^String repo-id]
   (deps-repo-by-id repo-id (read-settings) (read-settings-security)))
  ([^String repo-id ^Settings settings ^SettingsSecurity settings-security]
   {repo-id (get (deps-repositories settings settings-security) repo-id)}))

(comment
  (require '[deps-deploy.maven-settings :as mvn])

  (let [settings (mvn/read-settings)
        settings-security (mvn/read-settings-security)
        encoded-master-pw (.getMaster settings-security)
        plain-master-pw (mvn/decode-password encoded-master-pw)
        servers (.getServers settings)]
    (doseq [s servers]
      (let [plain-pw (if (mvn/encoded-pw? (.getPassword s))
                       (mvn/decode-password (.getPassword s) plain-master-pw)
                       (.getPassword s))]
        (println "----------")
        (println "Credentials for server" (.getId s) "are")
        (println "Username :" (.getUsername s))
        (println "Password :" plain-pw))))

  (mvn/servers-with-passwords)

  (mvn/read-settings default-settings-path)

  (mvn/active-profiles (mvn/read-settings))

  (mvn/active-repositories (mvn/read-settings))

  (mvn/deps-repositories)

  0)
