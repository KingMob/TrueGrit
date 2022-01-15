(ns net.modulolotus.truegrit.circuit-breaker-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [net.modulolotus.truegrit :refer [with-circuit-breaker]]
            [net.modulolotus.truegrit.circuit-breaker :refer [call-allowed? circuit-breaker metrics retrieve wrap state-name]]
            [net.modulolotus.truegrit.test-util :refer [always-throws fail-n-cycle fail-n-times returns-after-awhile returns-input throws-arithmetic-exception]])
  (:import (io.github.resilience4j.circuitbreaker CallNotPermittedException)
           (java.time Duration)))

(def default-cb-name "test")
(def default-test-config {:name                                         default-cb-name
                          :failure-rate-threshold                       25.0
                          :minimum-number-of-calls                      4
                          :permitted-number-of-calls-in-half-open-state 1
                          :wait-duration-in-open-state                  60000})

(def always-throws-regex #"Always throws")
(def retry-throws-arithmetic-regex #"2\+2")
(def fail-n-times-regex #"Not ready yet")


(deftest basic-config
  (let [cb-returns-input (with-circuit-breaker returns-input default-test-config)
        cb-config (.getCircuitBreakerConfig (retrieve cb-returns-input))]

    (is (= (:failure-rate-threshold default-test-config)
           (.getFailureRateThreshold cb-config)))
    (is (= (:minimum-number-of-calls default-test-config)
           (.getMinimumNumberOfCalls cb-config)))
    (is (= (:permitted-number-of-calls-in-half-open-state default-test-config)
           (.getPermittedNumberOfCallsInHalfOpenState cb-config)))
    (is (= (Duration/ofMillis (:wait-duration-in-open-state default-test-config))
           (.getWaitDurationInOpenState cb-config)))))

(deftest basic-operation
  (let [resil-returns (with-circuit-breaker returns-input default-test-config)]
    (is (= :foo (resil-returns :foo)))
    (is (= 1 (:number-of-successful-calls (metrics (retrieve resil-returns)))))))


(deftest basic-failure
  (let [resil-throw (with-circuit-breaker always-throws default-test-config)]
    (is (thrown-with-msg? Exception always-throws-regex (resil-throw)))
    (is (= 1 (:number-of-failed-calls (metrics (retrieve resil-throw)))))))

(deftest cb-status
  (testing "Successful - breaker remains CLOSED"
    (let [max-attempts (:minimum-number-of-calls default-test-config)
          resil-returns (with-circuit-breaker returns-input (merge default-test-config
                                                                   {:name "closed"}))]
      (dotimes [_ max-attempts]
        (is (= :success (resil-returns :success))))
      (let [cb-metrics (metrics (retrieve resil-returns))]
        (is (= (:minimum-number-of-calls default-test-config)
               (:number-of-successful-calls cb-metrics)))
        (is (zero? (:failure-rate cb-metrics)))
        (is (= :closed (state-name (retrieve resil-returns))))
        (is (.tryAcquirePermission (retrieve resil-returns))))))

  (testing "Failing - breaker becomes OPEN"
    (let [cycle-len (:minimum-number-of-calls default-test-config)
          flaky-fn (fail-n-cycle (Math/ceil (/ cycle-len 2)) cycle-len) ; fails 50% of the time
          resil-fn (with-circuit-breaker flaky-fn (merge default-test-config
                                                         {:name "open"}))]
      (dotimes [_ cycle-len]
        (try
          (resil-fn)
          (catch Exception _)))

      (let [cb-metrics (metrics (retrieve resil-fn))]
        (is (= 50.0 (:failure-rate cb-metrics)))
        (is (= :open (state-name (retrieve resil-fn))))
        (is (not (.tryAcquirePermission (retrieve resil-fn))))))))


(deftest calls-allowed
  (testing "solo circuit breaker health"
    (let [cb (circuit-breaker default-cb-name default-test-config)]
      (is (call-allowed? cb))

      (.transitionToOpenState cb)
      (is (not (call-allowed? cb)))

      (.transitionToHalfOpenState cb)
      (is (call-allowed? cb)))))


(deftest advanced-config-options
  (testing "ignore-exceptions"
    (let [cb (circuit-breaker default-cb-name {:ignore-exceptions [ArithmeticException]})
          cb-throws-arithmetic (wrap throws-arithmetic-exception cb)]
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception retry-throws-arithmetic-regex (cb-throws-arithmetic)))
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))))

  (testing "record-exceptions"
    (let [cb (circuit-breaker default-cb-name {:record-exceptions [ArithmeticException]})
          cb-always-throws (wrap always-throws cb)
          cb-throws-arithmetic (wrap throws-arithmetic-exception cb)]

      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception always-throws-regex (cb-always-throws)))
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))

      (.reset cb)

      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception retry-throws-arithmetic-regex (cb-throws-arithmetic)))
      (is (= 1 (-> cb (metrics) :number-of-failed-calls)))))

  (testing "ignore-exception"
    (let [cb (circuit-breaker default-cb-name {:ignore-exception #(instance? ArithmeticException %)})
          cb-always-throws (wrap always-throws cb)
          cb-throws-arithmetic (wrap throws-arithmetic-exception cb)]
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception always-throws-regex (cb-always-throws)))
      (is (= 1 (-> cb (metrics) :number-of-failed-calls)))

      (.reset cb)

      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception retry-throws-arithmetic-regex (cb-throws-arithmetic)))
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))))

  (testing "window type"
    (testing "count-based"
      (let [window-count 10
            cb (circuit-breaker default-cb-name {:failure-rate-threshold  100
                                                 :minimum-number-of-calls window-count
                                                 :sliding-window-size     window-count
                                                 :sliding-window-type     :count})
            cb-always-throws (wrap always-throws cb)]
        (is (= :closed (state-name cb)))

        (dotimes [_ window-count]
          (try
            (cb-always-throws)
            (catch Exception _)))

        (is (= :open (state-name cb)))))

    (testing "time-based"
      ;; It's not feasible to test this realistically without introducing slowdown into the
      ;; unit tests by its very nature, because it's second-based.
      (let [window-time 2                                  ; i.e., 2 seconds
            cb (circuit-breaker default-cb-name {:failure-rate-threshold  100
                                                 :minimum-number-of-calls 1
                                                 :sliding-window-size     window-time
                                                 :sliding-window-type     :time})
            cb-always-throws (wrap always-throws cb)]
        (is (= :closed (state-name cb)))

        (dotimes [_ window-time]
          (try
            (cb-always-throws)
            (catch Exception _)))

        (is (= :open (state-name cb))))))

  (testing "record-exception"
    (let [cb (circuit-breaker default-cb-name {:record-exception #(instance? ArithmeticException %)})
          cb-always-throws (wrap always-throws cb)
          cb-throws-arithmetic (wrap throws-arithmetic-exception cb)]
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception always-throws-regex (cb-always-throws)))
      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))

      (.reset cb)

      (is (= 0 (-> cb (metrics) :number-of-failed-calls)))
      (is (thrown-with-msg? Exception retry-throws-arithmetic-regex (cb-throws-arithmetic)))
      (is (= 1 (-> cb (metrics) :number-of-failed-calls)))))

  (testing "slow calls"
    (let [cb (circuit-breaker default-cb-name {:minimum-number-of-calls      1
                                               :sliding-window-size          10
                                               :slow-call-duration-threshold 100
                                               :slow-call-rate-threshold     20})
          cb-returns-after-awhile (wrap returns-after-awhile cb)]
      (is (= 0 (-> cb (metrics) :number-of-slow-calls)))
      (cb-returns-after-awhile 100)                         ; > 50 ms threshold
      (is (= 1 (-> cb (metrics) :number-of-slow-calls)))
      (is (= 1 (-> cb (metrics) :number-of-slow-successful-calls)))
      (is (= 0 (-> cb (metrics) :number-of-slow-failed-calls)))
      (is (= :open (state-name cb))))))

(deftest event-listeners
  (let [event-count (atom 0)
        success-count (atom 0)
        error-count (atom 0)
        state-transition-count (atom 0)
        call-not-permitted-count (atom 0)
        reset-counts (fn []
                       (reset! event-count 0)
                       (reset! success-count 0)
                       (reset! error-count 0)
                       (reset! state-transition-count 0)
                       (reset! call-not-permitted-count 0))
        config (merge default-test-config
                      {:on-event              (fn [_] (swap! event-count inc))
                       :on-success            (fn [_] (swap! success-count inc))
                       :on-state-transition   (fn [_] (swap! state-transition-count inc))
                       :on-call-not-permitted (fn [_] (swap! call-not-permitted-count inc))
                       :on-error              (fn [_] (swap! error-count inc))})]
    (testing "good calls"
      (try
        (let [n (:minimum-number-of-calls config)
              resil-returns (with-circuit-breaker returns-input (merge config {:name "good"}))]
          (is (= 0 @event-count @success-count @error-count @state-transition-count @call-not-permitted-count))
          (dotimes [_ n]
            (is (= :success (resil-returns :success))))
          (is (= n @event-count @success-count))
          (is (= 0 @error-count @state-transition-count @call-not-permitted-count)))
        (finally
          (reset-counts))))

    (testing "always failing calls"
      (try
        (let [n (:minimum-number-of-calls config)
              resil-throws (with-circuit-breaker always-throws (merge config {:name "failing"}))]
          (is (= 0 @event-count @success-count @error-count @state-transition-count @call-not-permitted-count))

          ;; Make exactly ring-buffer-size failing calls
          (dotimes [_ n]
            (is (thrown-with-msg? Exception always-throws-regex (resil-throws))))

          (is (= n @error-count))
          (is (= 1 @state-transition-count))
          (is (= 0 @success-count @call-not-permitted-count))

          ;; Try just 1 more, and the call will be forbidden
          (is (thrown? CallNotPermittedException (resil-throws)))

          (is (= 1 @call-not-permitted-count)))
        (finally
          (reset-counts))))

    (testing "flaky call"
      (try
        (let [fail-n (:minimum-number-of-calls config)
              flaky-fn (fail-n-times fail-n)
              cb (circuit-breaker "flaky" config)
              resil-fn (wrap flaky-fn cb)]

          (is (= 0 @event-count @success-count @error-count @state-transition-count @call-not-permitted-count))

          (dotimes [_ fail-n]
            (is (thrown-with-msg? Exception fail-n-times-regex (resil-fn))))

          (is (= fail-n @error-count))

          (is (= (state-name cb) :open))
          (is (thrown? CallNotPermittedException (resil-fn)))

          (.transitionToHalfOpenState cb)
          (is (= :success (resil-fn)))
          (is (= 1 @success-count))

          ;; CLOSED -> OPEN -> HALF-OPEN -> CLOSED
          (is (= 3 @state-transition-count))

          (is (= 1 @call-not-permitted-count)))
        (finally
          (reset-counts))))))
