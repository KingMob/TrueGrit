(ns net.modulolotus.truegrit.retry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [net.modulolotus.truegrit :refer [with-retry]]
            [net.modulolotus.truegrit.retry
             :refer [metrics retrieve retry wrap]]
            [net.modulolotus.truegrit.test-util :refer [always-throws fail-n-times returns-input throws-arithmetic-exception]]
            [net.modulolotus.truegrit.util :as util])
  (:import (clojure.lang ExceptionInfo)))

(def default-test-config {:name          "test"
                          :max-attempts  3
                          :wait-duration 500})

(def always-throws-regex #"Always throws")
(def retry-throws-arithmetic-regex #"2\+2")

(deftest basic-config
  (let [resil-returns (with-retry returns-input default-test-config)
        rt-config (.getRetryConfig (retrieve resil-returns))]

    (is (= (:max-attempts default-test-config)
           (.getMaxAttempts rt-config)))))

(deftest basic-operation
  (let [resil-returns (with-retry returns-input {:name "test"
                                                 :wait-duration 50})]
    (is (= :foo (resil-returns :foo)))
    (is (= (:number-of-successful-calls-without-retry-attempt (metrics (retrieve resil-returns))) 1))))


(deftest basic-failure
  (let [max-attempts 4
        resil-throw (with-retry always-throws {:name "test"
                                               :max-attempts max-attempts
                                               :wait-duration 50})]
    (is (thrown-with-msg? ExceptionInfo always-throws-regex (resil-throw)))
    (is (= (:number-of-failed-calls-with-retry-attempt (metrics (retrieve resil-throw))) 1))))

(deftest retry-until-success
  (let [max-attempts 4
        flaky-fn (fail-n-times (dec max-attempts))
        resil-fn (with-retry flaky-fn {:name "test"
                                       :max-attempts max-attempts
                                       :wait-duration 50})]
    (is (= :success (resil-fn)))
    (is (= (:number-of-successful-calls-with-retry-attempt (metrics (retrieve resil-fn))) 1))))

(deftest advanced-config-options
  ;; Tests with- and without- metrics to show when they are ignored. But without eventual success,
  ;; will still return failure
  (testing "ignore-exceptions"
    (let [rt (retry "test" {:ignore-exceptions [ArithmeticException]})
          retry-throws-arithmetic (wrap throws-arithmetic-exception rt)]

      (is (= 0 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))
      (is (thrown-with-msg? ArithmeticException retry-throws-arithmetic-regex (retry-throws-arithmetic)))
      (is (= 1 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))))

  (testing "retry-exceptions"
    (let [rt (retry "test" {:retry-exceptions [ArithmeticException]
                            :wait-duration 50})
          retry-throws-exception-info (wrap always-throws rt)
          retry-throws-arithmetic (wrap throws-arithmetic-exception rt)]
      (is (= 0 (-> rt (metrics) :number-of-failed-calls-with-retry-attempt)))
      (is (thrown-with-msg? ArithmeticException retry-throws-arithmetic-regex (retry-throws-arithmetic)))
      (is (= 1 (-> rt (metrics) :number-of-failed-calls-with-retry-attempt)))

      (is (= 0 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))
      (is (thrown-with-msg? ExceptionInfo always-throws-regex (retry-throws-exception-info)))
      (is (= 1 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))))

  (testing "retry-on-exception"
    (let [rt (retry "test" {:max-attempts    3
                            :retry-on-exception #(instance? ArithmeticException %)
                            :wait-duration 50})
          retry-throws-exception-info (wrap always-throws rt)
          retry-throws-arithmetic (wrap throws-arithmetic-exception rt)]
      (is (= 0 (-> rt (metrics) :number-of-failed-calls-with-retry-attempt)))
      (is (thrown-with-msg? ArithmeticException retry-throws-arithmetic-regex (retry-throws-arithmetic)))
      (is (= 1 (-> rt (metrics) :number-of-failed-calls-with-retry-attempt)))

      (is (= 0 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))
      (is (thrown-with-msg? ExceptionInfo always-throws-regex (retry-throws-exception-info)))
      (is (= 1 (-> rt (metrics) :number-of-failed-calls-without-retry-attempt)))))

  (testing "retry-on-result"
    (testing "no retries"
      (let [rt (retry "test" {:max-attempts    3
                              :retry-on-result number?
                              :wait-duration 50})
            resil-returns-input (wrap returns-input rt)]
        (is (= 0 (-> rt (metrics) :number-of-successful-calls-without-retry-attempt)))
        (resil-returns-input "doesn't retry on strings")
        (is (= 1 (-> rt (metrics) :number-of-successful-calls-without-retry-attempt)))))

    (testing "no success"
      (let [rt (retry "test" {:max-attempts    3
                              :retry-on-result number?
                              :wait-duration   50})
            resil-returns-input (wrap returns-input rt)]
        (is (= 0 (-> rt (metrics) :number-of-successful-calls-with-retry-attempt)))
        (resil-returns-input 12345)     ; does retry on numbers
        (is (= 1 (-> rt (metrics) :number-of-failed-calls-with-retry-attempt)))))))

(deftest event-listeners
  (let [max-attempts 3
        event-count (atom 0)
        success-count (atom 0)
        retry-count (atom 0)
        error-count (atom 0)
        reset-counts (fn []
                      (reset! event-count 0)
                      (reset! success-count 0)
                      (reset! retry-count 0)
                      (reset! error-count 0))
        config {:name         "test"
                :max-attempts max-attempts
                :wait-duration 50
                :on-event     (fn [_] (swap! event-count inc))
                :on-success   (fn [_] (swap! success-count inc))
                :on-retry     (fn [_] (swap! retry-count inc))
                :on-error     (fn [_] (swap! error-count inc))}]
    (testing "good call"
      (try
        (let [resil-returns (with-retry returns-input config)]
          (is (= 0 @event-count @success-count @retry-count @error-count))
          (resil-returns :foo)
          (is (= 0 @event-count @success-count @retry-count @error-count)))
        (finally
          (reset-counts))))

    (testing "failing call"
      (try
        (let [resil-throws (with-retry always-throws config)]
          (is (= 0 @event-count @success-count @retry-count @error-count))

          (is (thrown-with-msg? ExceptionInfo always-throws-regex (resil-throws)))

          (is (= 3 @event-count))
          (is (= 2 @retry-count))
          (is (= 0 @success-count))
          (is (= 1 @error-count)))
        (finally
          (reset-counts))))

    (testing "flaky call"
      (try
        (let [flaky-fn (fail-n-times (dec max-attempts))
              resil-fn (with-retry flaky-fn config)]
          (is (= 0 @event-count @success-count @retry-count @error-count))

          (is (= :success (resil-fn)))

          (is (= 3 @event-count))
          (is (= 2 @retry-count))
          (is (= 1 @success-count))
          (is (= 0 @error-count)))
        (finally
          (reset-counts))))))

(deftest interval-function
  (let [intervals (atom [])
        interval-fn (util/fn->interval-function
                      (fn [n]
                        (let [wait (* n 10)]
                          (swap! intervals conj wait)
                          wait)))
        rt (retry "test" {:name              "test"
                          :interval-function interval-fn})
        resil-fail-n-times (wrap (fail-n-times 2) rt)]
    (is (= :success (resil-fail-n-times)))
    (is (= [10 20] @intervals))))

(deftest interval-bi-function
  (let [intervals (atom [])
        interval-bi-fn (util/fn->interval-bi-function
                         (fn [n throwable-or-result]
                           (let [wait (+ (* n 10)
                                         (if (instance? ExceptionInfo throwable-or-result)
                                           1
                                           0))]
                             (swap! intervals conj wait)
                             wait)))
        rt (retry "test" {:name              "test"
                          :interval-bi-function interval-bi-fn})
        resil-fail-n-times (wrap (fail-n-times 2) rt)]
    (is (= :success (resil-fail-n-times)))
    (is (= [11 21] @intervals))))