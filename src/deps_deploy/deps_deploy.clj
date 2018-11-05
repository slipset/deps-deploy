(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.data.xml :as xml]))

(def default-repo-settings {"clojars" {:url "https://clojars.org/repo"
                                       :username (System/getenv "CLOJARS_USERNAME")
                                       :password (System/getenv "CLOJARS_PASSWORD")}})

(def name-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/name)
(def version-tag :xmlns.http%3A%2F%2Fmaven.apache.org%2FPOM%2F4.0.0/version)

(defn name-version-from-pom [pom-str]
  (->> pom-str
       xml/parse-str
       :content
       (remove string?)
       (keep (fn [{:keys [tag] :as m}]
               (when (or (= tag
                            name-tag)
                         (= tag
                            version-tag))
                 {(keyword (name tag)) (first (:content m))})))
       (apply merge)))

(defmulti deploy :installer)

(defmethod deploy :clojars [{:keys [artifact name version repository]
                             :or {repository default-repo-settings} :as opts }]
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

(defn -main [deploy-or-install artifact & _]
  (let [artifact-info (-> {:installer (cond (= "deploy" deploy-or-install) :clojars
                                            (= "install" deploy-or-install) :local)
                           :artifact artifact}
                          (merge (name-version-from-pom (slurp "pom.xml")))
                          (update :name symbol))]
    (deploy artifact-info)))
