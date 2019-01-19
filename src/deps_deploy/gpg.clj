(ns deps-deploy.gpg
  (:import [java.lang Runtime]
           [java.io ByteArrayOutputStream
            Console]))

(defn gpg-program []
  (or (System/getenv "DEPS_DEPLOY_GPG") "gpg"))

(defn read-passphrase []
  (let [console (System/console)]
    (String. (.readPassword console "%s" (into-array ["gpg passphrase: "])))))

(defn gpg [{:keys [passphrase args]}]
  (try
    (let [runtime (Runtime/getRuntime)
          process (.exec runtime (into-array (into [(gpg-program)] args)))]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (.destroy process))))
      (with-open [out (.getInputStream process)
                  err-output (.getErrorStream process)
                  in (.getOutputStream process)]
        (when passphrase (spit in passphrase))
        (let [exit-code (.waitFor process)]
          {:exit-code exit-code
           :args (rest args)
           :success? (or (zero? exit-code) nil)
           :out (slurp out)
           :err (slurp err-output)})))
    (catch Exception e
      {:success? nil
       :args (rest args)
       :err e})))

(defn gpg-available? []
  (->> {:args ["--version"]} gpg :success?))

(defn sign-args [cmd file]
  (update cmd :args into ["--yes" "--armour" "--detach-sign" file]))

(defn add-passphrase [cmd passphrase]
  (-> cmd
      (update :args #(into ["--batch" "--pinentry-mode" "loopback" "--passphrase-fd" "0"] %))
      (assoc :passphrase passphrase)))

(defn sign! [passphrase file]
  (let [result (-> {}
                   (add-passphrase passphrase)
                   (sign-args file)
                   gpg)]
    (if (:success? result)
      (str file ".asc")
      (throw (Exception. (:err result))))))

(comment
  (defn -main [file]
    (let [passphrase (read-passphrase)]
      (->> file (sign! passphrase) println))))
