(ns deps-deploy.deps-deploy-test
  (:require
   [clojure.test :refer [deftest is]]
   [deps-deploy.deps-deploy :as sut])
  (:import
   (org.eclipse.aether.deployment DeploymentException)))

;; This is just a basic smoketest to ensure that:
;; - the deploy setup works
;; - the correct version of the maven HTTP transporter is used that
;;   throws a RFC9457-derived exception
(deftest deploying-to-clojars-with-non-token-throws-correct-exception
  (is (thrown-with-msg?
       DeploymentException #"status=401, title='No deploy token provided"
       (sut/deploy {:artifact       "test-resources/test-project/test-project-0.0.1.jar"
                    :installer      :remote
                    :pom-file       "test-resources/test-project/pom.xml"
                    :repository {:clojars {:username "test"
                                           :password "not-a-deploy-token"}}
                    :sign-releases? false}))))
