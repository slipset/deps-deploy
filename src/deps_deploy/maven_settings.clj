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
  "Reads settings-security.xml file into an ^org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity object."
  [^java.io.File file]
  (SecUtil/read (.getAbsolutePath file) true))

(defn ^org.apache.maven.settings.Settings read-settings
  "Reads settings.xml file into a ^org.apache.maven.settings.Settings object."
  [^java.io.File settings-path]
  (let [sr (SettingsXpp3Reader.)]
    (with-open [rdr (io/reader settings-path)]
      (.read sr rdr))))

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
    {:id (.getId server)
     :server-object server
     :username (.getUsername server)
     :password (decode-password (.getPassword server) plain-master-pw)}))

(defn maven-servers-with-passwords
  "Reads the servers from maven settings.xml and settings-security.xml files.
   Decodes the passwords."
  [opts]
  (let [{:keys [settings settings-security]} opts
        settings (or settings default-settings-path)
        settings-security (or settings-security default-settings-security-path)
        settings (read-settings (io/as-file settings))
        settings-security (read-settings-security (io/as-file settings-security))
        encoded-master-pw (.getMaster settings-security)
        plain-master-pw (decode-master-password encoded-master-pw)
        servers (.getServers settings)
        map-server (map-server-factory plain-master-pw)]
    (map map-server servers)))


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

  (maven-servers-with-passwords nil)

0)
