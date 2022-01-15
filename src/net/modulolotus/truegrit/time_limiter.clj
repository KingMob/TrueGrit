(ns net.modulolotus.truegrit.time-limiter
  "Implements time limiters, aka timeouts, which throw exceptions if a fn takes too long to return.

   See https://resilience4j.readme.io/docs/timeout"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.timelimiter TimeLimiter TimeLimiterConfig)
           (java.util.concurrent TimeoutException)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(defn time-limiter-config
  "Creates a Resilience4j TimeLimiterConfig.

   Config map options
    - `:cancel-running-future?` - should `.cancel()` be called on the running Future? - defaults to true
    - `:timeout-duration` - how long to wait before timing out - defaults to 1000 ms - accepts number of ms or java.time.Duration"
  ^TimeLimiterConfig
  [config]
  (let [{:keys [cancel-running-future?
                timeout-duration]} (merge *default-config* config)]
    (-> (TimeLimiterConfig/custom)
        (cond->
          cancel-running-future?
          (.cancelRunningFuture cancel-running-future?)

          timeout-duration
          (.timeoutDuration (util/ms-duration timeout-duration)))
        (.build))))

(defn add-listeners
  "Add event handlers for TimeLimiter lifecycle events.

   Config map options
    - `:on-success` - a handler that runs after a successful call - receives a TimeLimiterOnSuccessEvent
    - `:on-error` - a handler that runs after an error - receives a TimeLimiterOnErrorEvent
    - `:on-timeout` - a handler that runs after a call times out - receives a TimeLimiterOnTimeoutEvent"
  [^TimeLimiter tl {:keys [on-success on-error on-timeout]}]
  (let [ep (.getEventPublisher tl)]
    (when on-success (.onSuccess ep (util/fn->event-consumer on-success)))
    (when on-error (.onError ep (util/fn->event-consumer on-error)))
    (when on-timeout (.onTimeout ep (util/fn->event-consumer on-timeout)))))

(defn time-limiter
  "Creates a TimeLimiter"
  ^TimeLimiter
  [config]
  (doto (TimeLimiter/of (time-limiter-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a time-limiter from a wrapped fn"
  ^TimeLimiter
  [f]
  (-> f meta :truegrit/time-limiter))

(defn wrap
  "Wraps a function in a TimeLimiter. Throws TimeoutException if it times out.

   Attaches the time limiter as metadata on the wrapped fn at :truegrit/time-limiter"
  [f ^TimeLimiter tl]
  (-> (fn [& args]
        (let [future-supplier (apply util/fn->future-supplier f args)
              tl-callable (TimeLimiter/decorateFutureSupplier tl future-supplier)]
          (log/debug "Running with time limit" {:timeout-duration (.getTimeoutDuration (.getTimeLimiterConfig tl))})
          (try
            (.call tl-callable)

            (catch TimeoutException e
              (log/trace e "fn timed out" {:fn f})
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/time-limiter tl))))


