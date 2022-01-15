(ns net.modulolotus.truegrit.rate-limiter
  "Implements rate limiters, which throttle fns that are called too often.

   See https://resilience4j.readme.io/docs/ratelimiter"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.ratelimiter RateLimiter RateLimiterConfig RequestNotPermitted)
           (java.time Duration)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(defn rate-limiter-config
  "Creates a Resilience4j RateLimiterConfig.

   Config map options
    - `:limit-for-period` - the number of calls allowed per time window - defaults to 50
    - `:limit-refresh-period` - the Duration of the time window - defaults to 500 nanoseconds - accepts number of milliseconds or java.time.Duration
    - `:timeout-duration` - how long to wait to be allowed to proceed before timing out - defaults to 5000 ms - accepts number of milliseconds or java.time.Duration
"
  ^RateLimiterConfig
  [config]
  (let [{:keys [limit-for-period
                limit-refresh-period
                timeout-duration]} (merge *default-config* config)]
    (-> (RateLimiterConfig/custom)
        (cond->
          limit-for-period
          (.limitForPeriod limit-for-period)

          limit-refresh-period
          (.limitRefreshPeriod (util/ms-duration limit-refresh-period))

          timeout-duration
          (.timeoutDuration (util/ms-duration timeout-duration)))
        (.build))))

(defn add-listeners
  "Add event handlers for RateLimiter lifecycle events.

   Config map options:
    - `:on-success` - a handler that runs after a successful call - receives a RateLimiterOnSuccessEvent
    - `:on-failure` - a handler that runs after an failure - receives a RateLimiterOnFailureEvent"
  [^RateLimiter rl {:keys [on-success on-failure]}]
  (let [ep (.getEventPublisher rl)]
    (when on-success (.onSuccess ep (util/fn->event-consumer on-success)))
    (when on-failure (.onFailure ep (util/fn->event-consumer on-failure)))))

(defn rate-limiter
  "Creates a RateLimiter"
  ^RateLimiter
  [^String rate-limiter-name config]
  (doto (RateLimiter/of rate-limiter-name (rate-limiter-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a rate-limiter from a wrapped fn"
  ^RateLimiter
  [f]
  (-> f meta :truegrit/rate-limiter))

(defn metrics
  "Returns metrics for the given rate limiter. Mostly useful for
   debugging.

   Available permission count is an estimate. Can be negative if
   some permissions were reserved.

   The number of waiting threads is also only an estimate."
  [^RateLimiter rl]
  (let [rl-metrics (.getMetrics rl)]
    {:available-permissions     (.getAvailablePermissions rl-metrics)
     :number-of-waiting-threads (.getNumberOfWaitingThreads rl-metrics)}))

(defn wrap
  "Wraps a fn in a RateLimiter. Throws RequestNotPermitted if permission to run was not acquired.

   Attaches the rate limiter as metadata on the wrapped fn at :truegrit/rate-limiter"
  [f ^RateLimiter rl]
  (-> (fn [& args]
        (let [callable (apply util/fn->callable f args)
              rl-callable (RateLimiter/decorateCallable rl callable)]
          (log/debug "Running with time limit" {:timeout-duration (.getTimeoutDuration (.getRateLimiterConfig rl))})
          (try
            (.call rl-callable)

            (catch RequestNotPermitted e
              (log/trace e "Rate exceeded" {:fn f})
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/rate-limiter rl))))

