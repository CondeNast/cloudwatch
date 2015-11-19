(ns cloudwatch.core
  (:require [amazonica.aws.cloudwatch :as cloudwatch]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-http.client :as http]
            [clj-time.format :as tf]
            [taoensso.timbre  :as logger]
            ))

(def cloudwatch-resp (atom nil))
(def cloudwatch-pending (atom []))

(def meta-data-host (memoize (fn [] "169.254.169.254")))
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
      )
      @cloudwatch-resp)

(comment
  (collect-total-metrics
      [ {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions nil :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now)                 :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp  nil    :dimensions {:server "a"} :value 1}
        {:namespace "a" :metric-name "test" :unit "Count" :timestamp  nil    :dimensions {:server "b"} :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "b" :metric-name "test" :unit "Count" :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "b" :metric-name "test2"              :timestamp (t/now) :dimensions {} :value 1}
        {:namespace "a" :metric-name "test2" :unit "Count" :timestamp (t/now) :dimensions {} :value 3}])
  )

(defn collect-total-metrics
  "Collects all the metrics that have been recorded locally, and combines them into statistic-sets
   metrics with the same name and dimensions should have the same units / values"
  [metric-datum]
  (let [collected-metrics
        (reduce (fn [coll metric]
                  (let [key-str (clojure.string/join [(:namespace metric) (:metric-name metric) (json/write-str (or (:dimensions metric) {}))])]
                    (update coll key-str conj metric))) {} metric-datum)

        statistic-sets (map (fn [[_ metrics]]
                              (let [unit (some :unit metrics)
                                    dimensions (some :dimensions metrics)
                                    timestamp (last (filter identity (map :timestamp metrics)))
                                    namespace (:namespace (first metrics))

                                    values (filter identity (map :value metrics))
                                    stats {:minimum (apply min values)
                                           :maximum (apply max values)
                                           :sample-count (count values)
                                           :sum (apply + values)}

                                    datum (cond-> {:metric-name (:metric-name (first metrics))
                                                   :statistic-values stats}
                                             unit (assoc :unit unit)
                                             dimensions (assoc :dimensions dimensions))
                                    ]
                                {:namespace namespace
                                 :metric-data datum}
                                )) collected-metrics)
                             ]
      statistic-sets))

(defn put-all-metrics
  []
  (swap! cloudwatch-pending (fn [pending]
    (try
      (doseq [chunk (partition-all 20 (collect-total-metrics pending))]
        (do
          (logger/info "Putting Metrics: " (count chunk))
          (cloudwatch/put-metric-data (cloudwatch-cred) chunk)
          (Thread/sleep 250)
          ))
      []
      (catch Exception e (logger/error "Metrics Reporting Error: " (pr-str e)))))))

(def cloudwatch-processing-future (atom nil))
(def cloudwatch-processing-running (atom false))

(defn stop-cloudwatch-processing
  []

  (reset! cloudwatch-processing-running false)

  (if (future? @cloudwatch-processing-future)
    (if (not (future-done? @cloudwatch-processing-future))
      (if (not (future-cancelled? @cloudwatch-processing-future))
        (future-cancel @cloudwatch-processing-future))))

  (reset! cloudwatch-processing-future nil))

(defn start-cloudwatch-processing
  [& opts]
  (let [args (apply hash-map opts)
        update-rate (or (:update-rate args) 60000)]

    (reset! cloudwatch-processing-running true)

    (if (not @cloudwatch-processing-future)
        (reset! cloudwatch-processing-future
              (do
                (future
                  (while @cloudwatch-processing-running
                     (do
                          (Thread/sleep update-rate)
                          (put-all-metrics)))))))))

(defn metric
  ([namespace metric-name metric-dimensions metric-unit ] (metric namespace metric-name metric-dimensions metric-unit 1))
  ([namespace metric-name metric-dimensions metric-unit metric-value]

    (let [dimensions (map #(hash-map :name (key %1) :value (val %1)) metric-dimensions)]
      (start-cloudwatch-processing)
      (swap! cloudwatch-pending conj { :namespace namespace
                                       :metric-name metric-name
                                       :unit metric-unit
                                       :timestamp (t/now)
                                       :dimensions dimensions
                                       :value metric-value}))))
