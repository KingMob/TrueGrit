(ns net.modulolotus.truegrit.bulkhead-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [net.modulolotus.truegrit :refer [with-bulkhead]]
            [net.modulolotus.truegrit.bulkhead :refer [bulkhead metrics wrap]]
            [net.modulolotus.truegrit.test-util :refer [returns-input]])
  (:import (io.github.resilience4j.bulkhead BulkheadFullException)))

(deftest create-bh
  (let [max-concurrent-calls 123
        bh (bulkhead "test" {:max-concurrent-calls max-concurrent-calls})
        bh-metrics (metrics bh)]
    (is (= max-concurrent-calls
           (:available-concurrent-calls bh-metrics)
           (:max-allowed-concurrent-calls bh-metrics)))))

(deftest basic-bulkhead-operation
  (let [max-concurrent-calls 10
        bh (bulkhead "test" {:max-concurrent-calls max-concurrent-calls})
        bh-metrics (metrics bh)
        resil-returns-input (wrap returns-input bh)]

    ;; We don't expect the available calls to visibly decrease without multi-threaded code
    (is (= max-concurrent-calls (:available-concurrent-calls bh-metrics)))
    (resil-returns-input :foo)
    (is (= max-concurrent-calls (:available-concurrent-calls (metrics bh))))))

(deftest exceptions
  (let [bh-name "exceptions-bh"
        max-concurrent-calls 5
        bh (bulkhead bh-name {:name                 bh-name
                              :max-concurrent-calls max-concurrent-calls})
        resil-returns-input (wrap returns-input bh)]

    ;; Take all available bulkhead permissions
    (dotimes [_ max-concurrent-calls]
      (.acquirePermission bh))

    (is (thrown-with-msg? BulkheadFullException (re-pattern bh-name) (resil-returns-input)))
    (.releasePermission bh)
    (is (= :foo (returns-input :foo)))))
