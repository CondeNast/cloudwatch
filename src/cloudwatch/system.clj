(ns cloudwatch.system
  (:require [cloudwatch.core :refer [metric aws-instance-id]]))

(defn free-jvm-memory []
  (.freeMemory (java.lang.Runtime/getRuntime)))

(defn available-jvm-memory []
  (.totalMemory (java.lang.Runtime/getRuntime)))

(defn max-jvm-memory []
  (.maxMemory (java.lang.Runtime/getRuntime)))

(def cloudwatch-memory-metrics-future (atom nil))

(defn start-memory-metrics
  [namespace]
  (if (not @cloudwatch-memory-metrics-future)
      (reset! cloudwatch-memory-metrics-future
              (do
                (future
                  (while true
                   (do
                        (Thread/sleep 30000)
                        (metric namespace "free-memory" {"instance-id" (aws-instance-id)} "Bytes" (free-jvm-memory))
                        (metric namespace "available-memory" {"instance-id" (aws-instance-id)} "Bytes" (available-jvm-memory))
                        (metric namespace "max-memory" {"instance-id" (aws-instance-id)} "Bytes" (max-jvm-memory) ))))))))

(defn stop-memory-metrics
  []
  (if @cloudwatch-memory-metrics-future
    (future-cancel @cloudwatch-memory-metrics-future))
  (reset! cloudwatch-memory-metrics-future nil))
