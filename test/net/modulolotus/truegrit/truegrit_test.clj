(ns net.modulolotus.truegrit.truegrit-test
  (:require [clojure.test :refer :all]
            [net.modulolotus.truegrit :as truegrit]))

(deftest multiple-policies-in-top-level-metadata
  (let [f (truegrit/with-time-limiter inc {})
        f (truegrit/with-retry f {})
        f (truegrit/with-circuit-breaker f {})
        f (truegrit/with-rate-limiter f {})
        f (truegrit/with-bulkhead f {})
        meta-ks (-> f meta keys set)
        expected #{:truegrit/time-limiter
                   :truegrit/retry
                   :truegrit/circuit-breaker
                   :truegrit/rate-limiter
                   :truegrit/bulkhead}]
    (is (= expected (clojure.set/intersection expected meta-ks)))))