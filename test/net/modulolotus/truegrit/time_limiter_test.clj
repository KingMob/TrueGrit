(ns net.modulolotus.truegrit.time-limiter-test
  (:require [clojure.test :refer [deftest is testing]]
            [net.modulolotus.truegrit :refer [with-time-limiter]]
            [net.modulolotus.truegrit.test-util
             :refer [always-throws returns-after-awhile returns-input]])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent TimeoutException)))

(deftest basic-operation
  (let [timeout-duration 200
        resil-returns (with-time-limiter returns-input {:timeout-duration timeout-duration})
        resil-returns-after-awhile (with-time-limiter returns-after-awhile {:timeout-duration timeout-duration})]
    (is (= :success (resil-returns :success)))
    (is (= :success (resil-returns-after-awhile (/ timeout-duration 2))))))

(deftest basic-timeout
  (let [sleep-duration 200
        resil-returns-after-awhile (with-time-limiter returns-after-awhile
                                                      {:timeout-duration (/ sleep-duration 10)})]
    (is (thrown? TimeoutException (resil-returns-after-awhile sleep-duration)))))

(deftest event-listeners
  (let [success-count (atom 0)
        error-count (atom 0)
        timeout-count (atom 0)
        reset-counts (fn []
                       (reset! success-count 0)
                       (reset! error-count 0)
                       (reset! timeout-count 0))
        config {:name             "test"
                :timeout-duration 50
                :on-success       (fn [_] (swap! success-count inc))
                :on-error         (fn [_] (swap! error-count inc))
                :on-timeout       (fn [_] (swap! timeout-count inc))}]
    (testing "success"
      (try
        (let [resil-returns (with-time-limiter returns-input config)]
          (is (= 0 @success-count @error-count @timeout-count))

          (resil-returns :foo)

          (is (= 1 @success-count))
          (is (= 0 @error-count @timeout-count)))
        (finally
          (reset-counts))))

    (testing "failures"
      (try
        (let [resil-throws (with-time-limiter always-throws config)]
          (is (= 0 @success-count @error-count @timeout-count))

          (is (thrown? ExceptionInfo (resil-throws)))

          (is (= 1 @error-count))
          (is (= 0 @success-count @timeout-count)))
        (finally
          (reset-counts))))

    (testing "times out"
      (try
        (let [timeout 100
              resil-fn (with-time-limiter returns-after-awhile
                                          (merge config
                                                 {:timeout-duration timeout}))]
          (is (= 0 @success-count @error-count @timeout-count))

          (is (thrown? TimeoutException (resil-fn (* 2 timeout))))

          (is (= 1 @timeout-count))
          (is (= 0 @success-count @error-count)))
        (finally
          (reset-counts))))))
