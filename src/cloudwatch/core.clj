(ns cloudwatch.core
  (:require [amazonica.aws.cloudwatch :as cloudwatch]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-http.client :as http]
            [clj-time.format :as tf]
            [environ.core :refer [env]]
            [taoensso.timbre  :as logger]
            ))
(def cloudwatch-resp (atom nil))
(def cloudwatch-pending (atom []))

(def meta-data-host (memoize (fn [] (or (env :meta-data-host) "169.254.169.254"))))
(def aws-instance-id (memoize (fn [] (slurp (str "http://" (meta-data-host) "/latest/meta-data/instance-id")))))


(defn cloudwatch-cred []
  (if (or (not @cloudwatch-resp)
          (t/after? (t/now) (:request-new-creds-at @cloudwatch-resp)))
    (let [resp (-> (str "http://" (meta-data-host) "/latest/meta-data/iam/security-credentials/aws-elasticbeanstalk-ec2-role")
                 (http/get  {:accept :json})
                 :body
                 (json/read-str)
                 (clojure.walk/keywordize-keys)
                 (clojure.set/rename-keys {:AccessKeyId :access-key :SecretAccessKey :secret-key :Token :session-token})
                )
          expiration (tf/parse (:Expiration resp))
          full-response
                 (assoc
                   (select-keys resp [:access-key :secret-key :session-token :request-new-creds-at])
                   :request-new-creds-at expiration)
         ]
      (reset! cloudwatch-resp full-response))
      ;full-response
      )
      @cloudwatch-resp)

(defn metric
  [metric-name metric-dimensions metric-unit metric-value]
  (let [dimensions (map #(hash-map :name (key %1) :value (val %1)) metric-dimensions)]
    (swap! cloudwatch-pending conj { :metric-name metric-name
                                   :unit metric-unit
                                   :dimensions dimensions
                                   :value metric-value})))
(defn put-all-metrics
  [namespace]
  (swap! cloudwatch-pending (fn [pending]
    (try
      (doseq [chunk (partition-all 20 pending)]
        (do
          (logger/info "Putting Metrics: " (count chunk))
          (cloudwatch/put-metric-data (cloudwatch-cred)
            :namespace namespace
            :metric-data chunk)))
      []
      (catch Exception e (logger/error "Metrics Reporting Error: " (pr-str e)))))))

(defn free-jvm-memory []
  (.freeMemory (java.lang.Runtime/getRuntime)))

(defn available-jvm-memory []
  (.totalMemory (java.lang.Runtime/getRuntime)))

(defn max-jvm-memory []
  (.maxMemory (java.lang.Runtime/getRuntime)))

(def cloudwatch-processing-future (atom nil))
(def cloudwatch-processing-running (atom false))

(defn stop-cloudwatch-processing
  []
  (future-cancel @cloudwatch-processing-future)
  (reset! cloudwatch-processing-future nil))

(defn start-cloudwatch-processing
  [cloudwatch-namespace]
  (println "STARTING: Collecting / Submitting memory CLoudwatch metrics")
  (if (not @cloudwatch-processing-future)
      (reset! cloudwatch-processing-future
              (do
                (println "Setting running to true..")
                (future
                  (while true
                   (do
                        (Thread/sleep 30000)
                        (println "Collecting / Submitting memory CLoudwatch metrics")
                        (metric "free-memory" {"instance-id" (aws-instance-id)} "Bytes" (free-jvm-memory))
                        (metric "available-memory" {"instance-id" (aws-instance-id)} "Bytes" (available-jvm-memory))
                        (metric "max-memory" {"instance-id" (aws-instance-id)} "Bytes" (max-jvm-memory) )
                        (put-all-metrics cloudwatch-namespace)))
                  )))))
