[![Clojars Project](https://img.shields.io/clojars/v/xcoo/deps-deploy.svg)](https://clojars.org/xcoo/deps-deploy)
# deps-deploy

A Clojure library to deploy your stuff to clojars with `clj` or `clojure`. It's a very thin wrapper around
Chas Emericks [pomegranate](https://github.com/clj-commons/pomegranate) library.

It will read your Clojars username/token from the environment variables `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`, and it will get your artifact-name and version from the `pom.xml`

## Usage

To deploy to Clojars, simply merge

```clojure
{:deploy {:extra-deps {xcoo/deps-deploy {:mvn/version "RELEASE"}}
          :exec-fn deps-deploy.deps-deploy/deploy
          :exec-args {:installer :remote
                       :sign-releases? true
                       :artifact "deps-deploy.jar"}}}
```
into your `deps.edn`, have a `pom.xml` handy (you can generate one with `clj -Spom`), and deploy with

```sh
$ env CLOJARS_USERNAME=username CLOJARS_PASSWORD=clojars-token clj -X:deploy
```

to deploy to Clojars.

It is also possible to override the default Clojars URL by supplying your own. For example:

```sh
$ env CLOJARS_URL=https://internal/repository/maven-releases CLOJARS_USERNAME=username CLOJARS_PASSWORD=password clj -A:deploy
```
This facilitates deploying artefacts to an internal repository - perhaps a proxy service that is running locally that is used
to hold private JARs etc...

If you want to sign using another key than the default key, you can specify the signing key id:

```clojure
{:deploy {:extra-deps {xcoo/deps-deploy {:mvn/version "RELEASE"}}
          :exec-fn deps-deploy.deps-deploy/deploy
          :exec-args {:installer :remote
                       :sign-releases? true
                       :sign-key-id "1C33430999AA1C3C243A302689CACBAD9979E3C5"
                       :artifact "deps-deploy.jar"}}}
```

You can use `gpg --list-secret-keys --with-subkey-fingerprints` to find the key ids that are
known on your system.

### Deploy to private s3 buckets

To deploy to private s3 buckets, you first need to specify the `:repository` key in your `deps.edn` alias with `:exec-args` as:

```clj
:exec-args {:repository {"releases" {:url "s3p://my/bucket/"}}}
```
Then, when deploying, you need to provide credentials which is done either by:

1. setting the env vars: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
2. providing them via java system properties `aws.accessKeyId` and `aws.secretKey`
or

3. via an AWS credential profile, in the file `~/.aws/credentials` with the `AWS_PROIFLE` env var used to specify which profile to use (or the `[default]` profile).

```
[default]
aws_access_key_id = AKIAXXXXX
aws_secret_access_key = SECRET_KEY
```
For more details see [s3-wagon-provider](https://github.com/s3-wagon-private/s3-wagon-private#aws-credential-providers) and if you need to know how to [configure an S3 bucket see here](https://github.com/s3-wagon-private/s3-wagon-private#aws-policy).

### A note on Clojars tokens

As of 2020-06-27, Clojars will no longer accept your Clojars password when deploying. You will have to use a token instead.
Please read more about this [here](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens)

Long story short, just go find yourself a token and use it in lieu of your password and you're done.

## Install locally

`deps-deploy` also supports installing to your local `.m2` repo, by invoking `install` instead of `deploy`:
```clojure
{:install {:extra-deps {xcoo/deps-deploy {:mvn/version "RELEASE"}}
           :exec-fn deps-deploy.deps-deploy/deploy
           :exec-args {:installer :local
                       :artifact "deps-deploy.jar"}}
```

## Signing

If you want to have your artifacts signed, add `"true"` as the last element of the `:main-opts` vector like so:
```clojure
:main-opts ["-m" "deps-deploy.deps-deploy" "install"
            "path/to/my.jar" "true"]
```

If you don't want to use the default key for signing, you can specify the key id:
```clojure
:main-opts ["-m" "deps-deploy.deps-deploy" "install"
            "path/to/my.jar" "true" "1C33430999AA1C3C243A302689CACBAD9979E3C5"]
```

## License

Copyright © 2018-2021 Erik Assum

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
