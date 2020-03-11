[![Clojars Project](https://img.shields.io/clojars/v/deps-deploy.svg)](https://clojars.org/deps-deploy)
# deps-deploy

A Clojure library to deploy your stuff to clojars with `clj` or `clojure`. It's a very thin wrapper around
Chas Emericks [pomegranate](https://github.com/cemerick/pomegranate) library.

It will read your Clojars username/password from the environment variables `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`, and it will get your artifact-name and version from the `pom.xml`

## Usage

To deploy to Clojars, simply merge

```clojure
{:deploy {:extra-deps {deps-deploy {:mvn/version "RELEASE"}}
          :main-opts ["-m" "deps-deploy.deps-deploy" "deploy"
		      "path/to/my.jar"]}}
```
into your `deps.edn`, have a `pom.xml` handy (you can generate one with `clj -Spom`), and deploy with

```sh
$ env CLOJARS_USERNAME=username CLOJARS_PASSWORD=password clj -A:deploy
```

to deploy to Clojars


`deps-deploy` also supports installing to your local `.m2` repo, by invoking `install` instead of `deploy`:
```clojure
{:install {:extra-deps {deps-deploy {:mvn/version "RELEASE"}}
           :main-opts ["-m" "deps-deploy.deps-deploy" "install"
			   "path/to/my.jar"]}}
```

## Signing

If you want to have your artifacts signed, add `"true"` as the last element of the `:main-opts` vector like so:
```clojure
:main-opts ["-m" "deps-deploy.deps-deploy" "install"
			   "path/to/my.jar" "true"]
 ```


## License

Copyright © 2018 Erik Assum

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
