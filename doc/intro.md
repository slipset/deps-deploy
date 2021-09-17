# Introduction to deps-deploy

deps-deploy allows you to deploy artifacts (jar files, zip files, etc) to Maven repositories like Maven Central https://search.maven.org/ .
To learn more about Maven repositories visit https://maven.apache.org/what-is-maven.html

## Examples

Deploy an artifact example

```clj

    (require '[deps-deploy.deps-deploy :as dd])
    (require '[deps-deploy.maven-settings :as maven])

    ;; Deploy artifact to remote repo
    (let [repo {"my-repo" {:url "my-repository"
                           :username (System/getenv "REPO_USERNAME")
                           :password (System/getenv "REPO_PASSWORD")}}]
        (dd/deploy {:installer :remote
                    :artifact "path-to-artifact.zip"
                    :pom-file "pom.xml" ;; pom containing artifact coordinates
                    :repository repo}))

    ;; Deploy to repository from settings.xml file that contains server with id *my-private-repo*
    ;; deps-repositories will decode encrypted passwords from settings.xml
    (let [repo {"my-private-repo" (get (maven/deps-repositories) "my-private-repo")}]
        (dd/deploy {:installer :remote
                    :artifact "path-to-artifact.zip"
                    :pom-file "pom.xml" ;; pom containing artifact coordinates
                    :repository repo}))

    ;; You can also use a convenience function
    (let [repo (maven/deps-repo-by-id "my-private-repo")]
        (dd/deploy {:installer :remote
                    :artifact "path-to-artifact.zip"
                    :pom-file "pom.xml" ;; pom containing artifact coordinates
                    :repository repo}))

```