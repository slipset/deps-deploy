(ns deps-deploy.deps-deploy
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]))

(def default-repo-settings {"clojars" {:url "https://clojars.org/repo"
                                       :username (System/getenv "CLOJARS_USERNAME")
                                       :password (System/getenv "CLOJARS_PASSWORD")}})
(defn deploy [{:keys [artifact name version repository]
               :or {repository default-repo-settings} :as opts }]
  (aether/deploy :pom-file "pom.xml"
                 :jar-file artifact
                 :repository repository
                 :coordinates [(symbol name) version] ))

(defn install [{:keys [artifact name version] :as opts}]
  (let [artifacts ["pom.xml" artifact]]
    (aether/install :jar-file artifact
                    :pom-file "pom.xml"
                    :transfer-listener :stdout
                    :coordinates [name version])))

(defn -main [deploy-or-install opts]
  (pp/pprint (edn/read-string opts))
  (cond (= "deploy" deploy-or-install) (deploy (edn/read-string opts))
        (= "install" deploy-or-install) (install (edn/read-string opts))))
