(ns net.modulolotus.truegrit.test-util)


(defn returns-input
  "Returns its input"
  [x]
  x)

(defn always-throws
  "Always throws an exception"
  []
  (throw (ex-info "Always throws" {})))

(defn fail-n-times
  "Return a function that throws an exception the first n times it's called, then returns :success"
  [n]
  (let [call-count (atom 0)]
    (fn []
      (if (< @call-count n)
        (do
          (swap! call-count inc)
          (throw (ex-info (str "Not ready yet (on failing call " @call-count "/" n ")") {:call-count @call-count})))
        :success))))

(defn fail-n-cycle
  "Return a function that throws an exception the first n times it's called, then return :success
  the remaining (cycle-length - n) of the time. Cycles around."
  [n cycle-len]
  (let [call-count (atom 0)]
    (fn []
      (let [cycle-call-count (mod @call-count cycle-len)]
        (swap! call-count inc)
        (if (< cycle-call-count n)
          (throw (ex-info (str "Not ready yet (on failing call " @call-count "/" n ")") {:call-count cycle-call-count}))
          :success)))))

(defn returns-after-awhile
  "Sleeps for n milliseconds, then returns :success"
  [n]
  (Thread/sleep n)
  :success)


(defn throws-arithmetic-exception
  [& _]
  (throw (ArithmeticException. "2+2 does not equal 4, no sir!")))
