(ns deps-deploy.gpg
  (:import [java.lang Runtime]
           [java.io ByteArrayOutputStream
            Console]))

(defn gpg-program []
  (or "gpg"))


(defn read-passphrase []
  (let [console (System/console)]
    (String. (.readPassword console "%s" (into-array ["gpg passphrase: "])))))

(defn gpg [{:keys [passphrase args result]}]

  (try
    (let [runtime (Runtime/getRuntime)
          args (if passphrase
                 (into ["--batch" "--pinentry-mode" "loopback" "--passphrase-fd" "0"] args)
                 args)
          args (into [(gpg-program)] args)
          process (.exec runtime (into-array args))]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (.destroy process))))
      (with-open [out (.getInputStream process)
                  err-output (.getErrorStream process)
                  in (.getOutputStream process)]
        (when passphrase
          (spit in passphrase))
        (let [exit-code (.waitFor process)]
          {:exit-code exit-code
           :args (rest args)
           :success? (or (zero? exit-code) nil)
           :out (slurp out)
           :err (slurp err-output)
           :result result})))
    (catch Exception e
      {:success? nil
       :args (rest args)
       :err e})))

(defn gpg-available? []
  (->> {:args ["--version"]} gpg :success?))

(defn sign-args [file]
  {:args ["--yes" "--armour" "--detach-sign" file]})

(defn add-passphrase [passphrase cmd]
  (assoc cmd :passphrase passphrase))

(defn sign! [passphrase file]
  (let [result (->> file
                    (sign-args)
                    (add-passphrase passphrase)
                    gpg)]
    (if (:success? result)
      (str file ".asc")
      (throw (Exception. (:err result))))))

(comment
  (defn -main [file]
    (let [passphrase (read-passphrase)]
      (->> file (sign! passphrase) println))))
