(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]))

(def default-repo-settings {"clojars" {:url "https://clojars.org/repo"
                                       :username (System/getenv "CLOJARS_USERNAME")
                                       :password (System/getenv "CLOJARS_PASSWORD")}})

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

(defn -main [deploy-or-install artifact name version]
  (let [artifact-info {:installer (cond (= "deploy" deploy-or-install) :clojars
                                        (= "install" deploy-or-install) :local)
                       :artifact artifact
                       :name (symbol name)
                       :version version}]
    (deploy artifact-info)))
