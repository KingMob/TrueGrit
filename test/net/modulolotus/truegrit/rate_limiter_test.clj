(ns net.modulolotus.truegrit.rate-limiter-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [net.modulolotus.truegrit :refer [with-rate-limiter]]
            [net.modulolotus.truegrit.rate-limiter :refer [rate-limiter retrieve]]
            [net.modulolotus.truegrit.test-util :refer [returns-input]])
  (:import (io.github.resilience4j.ratelimiter RequestNotPermitted)
           (java.time Duration)))

(def default-rl-name "test")
(def default-test-config {:name default-rl-name
                          :limit-for-period     50
                          :limit-refresh-period (Duration/ofNanos 500)
                          :timeout-duration     (Duration/ofSeconds 5)})

(defn rate-limiter-fixture
  [f]
  (binding []
    (f)))

(use-fixtures :each rate-limiter-fixture)


(deftest basic-config
  (let [resil-returns-input (with-rate-limiter returns-input default-test-config)
        rl-config (.getRateLimiterConfig (retrieve resil-returns-input))]

    (is (= (:limit-for-period default-test-config)
           (.getLimitForPeriod rl-config)))))

(deftest basic-operation
  (testing "manual sleep"
    (let [limit-for-period 1
          limit-refresh-period 50
          timeout-duration 0
          resil-returns (with-rate-limiter returns-input
                                           {:name                 default-rl-name
                                            :limit-for-period     limit-for-period
                                            :limit-refresh-period limit-refresh-period
                                            :timeout-duration     timeout-duration})]
      (is (= :foo (resil-returns :foo)))
      (Thread/sleep limit-refresh-period)
      (is (= :foo (resil-returns :foo)))))

  (testing "auto-wait for permission"
    (let [limit-for-period 1
          limit-refresh-period 50
          timeout-duration 1000
          resil-returns (with-rate-limiter returns-input
                                           {:name                 default-rl-name
                                            :limit-for-period     limit-for-period
                                            :limit-refresh-period limit-refresh-period
                                            :timeout-duration     timeout-duration})]
      (is (= :foo (resil-returns :foo)))
      (is (= :foo (resil-returns :foo))))))


(deftest basic-failure
  (let [limit-for-period 10
        limit-refresh-period 10000
        timeout-duration 0
        resil-returns (with-rate-limiter returns-input
                                         {:name                 default-rl-name
                                          :limit-for-period     limit-for-period
                                          :limit-refresh-period limit-refresh-period
                                          :timeout-duration     timeout-duration})]

    (dotimes [_ limit-for-period]
      (is (= :foo (resil-returns :foo))))
    (is (thrown? RequestNotPermitted (resil-returns :foo)))))


(deftest event-listeners
  (let [success-count (atom 0)
        failure-count (atom 0)
        reset-counts (fn []
                       (reset! success-count 0)
                       (reset! failure-count 0))
        config {:name                 default-rl-name
                :timeout-duration     0
                :limit-for-period     1
                :limit-refresh-period 100000000
                :on-success           (fn [_] (swap! success-count inc))
                :on-failure           (fn [_] (swap! failure-count inc))}]

    (testing "good call"
      (try
        (let [resil-returns (with-rate-limiter returns-input config)]
          (is (= 0 @success-count @failure-count))
          (resil-returns :foo)
          (is (= 1 @success-count))
          (is (= 0 @failure-count)))
        (finally
          (reset-counts))))

    (testing "too fast call"
      (try
        (let [resil-returns (with-rate-limiter returns-input config)]
          (is (= 0 @success-count @failure-count))
          (resil-returns :foo)
          (try (resil-returns :foo) (catch Exception _))
          (is (= 1 @success-count))
          (is (= 1 @failure-count)))
        (finally
          (reset-counts))))))

