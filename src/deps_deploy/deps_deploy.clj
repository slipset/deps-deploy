(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.data.xml :as xml]))

(def default-repo-settings {"clojars" {:url "https://clojars.org/repo"
                                       :username (System/getenv "CLOJARS_USERNAME")
                                       :password (System/getenv "CLOJARS_PASSWORD")}})

(def artifact-id-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/artifactId)
(def group-id-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/groupId)
(def version-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/version)

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

(defmulti deploy :installer)

(defmethod deploy :clojars [{:keys [artifact coordinates repository]
                             :or {repository default-repo-settings} :as opts }]
  (println "Deploying"  (str (first coordinates) "-" (second coordinates)) "to clojars as"
           (-> repository vals first :username))
  (aether/deploy :pom-file "pom.xml"
                 :jar-file artifact
                 :repository repository
                 :coordinates coordinates)
  (println "done."))

(defmethod deploy :local [{:keys [artifact coordinates]}]
  (println "Installing" (str (first coordinates) "-" (second coordinates)) "to your local `.m2`")
  (aether/install :jar-file (str artifact)
                  :pom-file "pom.xml"
                  :transfer-listener :stdout
                  :coordinates coordinates)
  (println "done."))

(defn -main [deploy-or-install artifact & _]
  (->> {:installer (cond (= "deploy" deploy-or-install) :clojars
                         (= "install" deploy-or-install) :local)
        :artifact artifact}
       (merge (coordinates-from-pom (slurp "pom.xml")))
       deploy))
