(ns net.modulolotus.truegrit.thread-pool-bulkhead-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [net.modulolotus.truegrit :refer [with-thread-pool-bulkhead]]
            [net.modulolotus.truegrit.thread-pool-bulkhead
             :refer [bulkhead metrics retrieve]]
            [net.modulolotus.truegrit.test-util :refer [returns-after-awhile returns-input]]
            [clojure.string :as str])
  (:import (java.util.concurrent Future)
           (io.github.resilience4j.bulkhead BulkheadFullException)))


(deftest create-pool
  (testing "fixed pool"
    (let [max-thread-pool-size 3
          core-thread-pool-size 1
          queue-capacity 123
          tp (bulkhead "test" {:max-thread-pool-size  max-thread-pool-size
                               :core-thread-pool-size core-thread-pool-size
                               :queue-capacity        queue-capacity})
          tp-metrics (metrics tp)]
      (is (= max-thread-pool-size (:maximum-thread-pool-size tp-metrics)))
      (is (= core-thread-pool-size (:core-thread-pool-size tp-metrics)))
      (is (= queue-capacity (:queue-capacity tp-metrics))))))

(deftest returns-future-value
  (testing "returns a future value"
    (let [tp-returns-input (with-thread-pool-bulkhead returns-input {:name "test"})
          result (tp-returns-input :foo)]
      (is (instance? Future result))
      (is (= :foo @result)))))

(deftest run-tasks
  (testing "running on another thread"
    (let [returns-thread-id #(-> (Thread/currentThread) (.getId))
          main-thread-id (returns-thread-id)
          returns-another-thread-id (with-thread-pool-bulkhead returns-thread-id {:name "test"})]
      (is (not= main-thread-id @(returns-another-thread-id))))))

(deftest exceptions
  (let [tp-name "anomalies-tp"
        max-thread-pool-size 1
        queue-capacity 1
        task-time-ms 1000
        resil-returns-after-awhile
        (with-thread-pool-bulkhead returns-after-awhile
                                   {:name                  tp-name
                                    :max-thread-pool-size  max-thread-pool-size
                                    :core-thread-pool-size 1
                                    :queue-capacity        queue-capacity
                                    :keep-alive-duration   (* 5 task-time-ms)})]

    ;; Fill up threads and queue
    (dotimes [_ (+ max-thread-pool-size queue-capacity)]
      (resil-returns-after-awhile task-time-ms))

    (try
      (resil-returns-after-awhile task-time-ms)
      (is false "Test should not reach here")
      (catch Exception e
        (is (instance? BulkheadFullException e))
        (is (str/includes? (.getMessage e) tp-name))))))
