(ns net.modulolotus.truegrit.circuit-breaker
  "Implements circuit breakers, which keep track of fn failures and temporarily disable
   a fn when it fails too often.

   See https://resilience4j.readme.io/docs/circuitbreaker"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.circuitbreaker CallNotPermittedException CircuitBreaker CircuitBreaker$State CircuitBreakerConfig CircuitBreakerConfig$SlidingWindowType)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(def ^:private cb-type
  {:count                                             CircuitBreakerConfig$SlidingWindowType/COUNT_BASED
   :time                                              CircuitBreakerConfig$SlidingWindowType/TIME_BASED
   CircuitBreakerConfig$SlidingWindowType/COUNT_BASED CircuitBreakerConfig$SlidingWindowType/COUNT_BASED
   CircuitBreakerConfig$SlidingWindowType/TIME_BASED  CircuitBreakerConfig$SlidingWindowType/TIME_BASED})

(defn circuit-breaker-config
  "Creates a Resilience4j CircuitBreakerConfig.

   Config map options
     - `:failure-rate-threshold` - Percentage from 1 to 100 - if more than this percentage of calls have failed, go to OPEN - defaults to 50%
     - `:slow-call-rate-threshold` - Percentage from 1 to 100 - if more than this percentage of calls are too slow, go to OPEN - defaults to 100%
     - `:slow-call-duration-threshold` - How slow is too slow, *even if* the call succeeds? All calls exceeding this duration are treated as failures - defaults to 60000 ms - accepts number of ms or java.time.Duration
     - `:permitted-number-of-calls-in-half-open-state` - # of calls to track when HALF-OPEN - defaults to 10
     - `:minimum-number-of-calls` - # of calls to keep track of - will not go to OPEN unless at least this many calls have been made - defaults to 100
     - `:sliding-window-type` - How the CB considers statistics. Either `:count` or `:time`. When count-based, examines the last n number of calls; when time-based, examines all calls from the last n seconds. (Time-based mimics Hystrix's behavior.) - defaults to `:count`
     - `:sliding-window-size` - The meaning depends on :sliding-window-type. When count-based, it's the number of calls to record; when time-based, it's the number of seconds. - defaults to 100
     - `:max-wait-duration-in-half-open-state` - How long to wait in HALF_OPEN before automatically switching to OPEN - 0 means wait until all calls have been completed - defaults to 0 ms - accepts number of ms or java.time.Duration
     - `:wait-duration-in-open-state` - How long to wait in OPEN before allowing a call again - defaults to 60000 ms - accepts number of ms or java.time.Duration

   Less common config map options
     - `:automatic-transition-from-open-to-half-open-enabled?` - Should it automatically transition to HALF-OPEN after the wait duration, or only after a call or check is made? - NB: when false, an OPEN state may actually allow calls if the wait duration has passed and nobody has tried yet, so don't rely on state, use [[call-allowed?]] - defaults to false
     - `:record-exceptions` - A coll of Throwables to record failure for - defaults to empty, which means all exceptions are recorded - `:ignore-exceptions` takes precedence over this
     - `:ignore-exceptions` - A coll of Throwables to ignore - e.g., `[IrrelevantException IgnoreThisException]` - are not counted as either successes *or* failures - defaults to empty
     - `:record-exception` - A 1-arg fn that tests a Throwable and returns true if it should be recorded as a failure, false if not - `:ignore-exception` predicate takes precedence
     - `:ignore-exception` - A 1-arg fn that tests a Throwable and returns true if it should be ignored, false if not
"
  ^CircuitBreakerConfig
  [config]
  (let [{:keys [automatic-transition-from-open-to-half-open-enabled?
                failure-rate-threshold
                minimum-number-of-calls
                permitted-number-of-calls-in-half-open-state
                max-wait-duration-in-half-open-state
                wait-duration-in-open-state
                sliding-window-type
                sliding-window-size
                slow-call-duration-threshold
                slow-call-rate-threshold
                ignore-exceptions
                record-exceptions
                ignore-exception
                record-exception]} (merge *default-config* config)]
    (-> (CircuitBreakerConfig/custom)
        (cond->
          automatic-transition-from-open-to-half-open-enabled?
          (.automaticTransitionFromOpenToHalfOpenEnabled automatic-transition-from-open-to-half-open-enabled?)

          failure-rate-threshold
          (.failureRateThreshold failure-rate-threshold)

          minimum-number-of-calls
          (.minimumNumberOfCalls minimum-number-of-calls)

          permitted-number-of-calls-in-half-open-state
          (.permittedNumberOfCallsInHalfOpenState permitted-number-of-calls-in-half-open-state)

          max-wait-duration-in-half-open-state
          (.maxWaitDurationInHalfOpenState (util/ms-duration max-wait-duration-in-half-open-state))

          wait-duration-in-open-state
          (.waitDurationInOpenState (util/ms-duration wait-duration-in-open-state))

          sliding-window-type
          (.slidingWindowType (cb-type sliding-window-type))

          sliding-window-size
          (.slidingWindowSize sliding-window-size)

          slow-call-duration-threshold
          (.slowCallDurationThreshold (util/ms-duration slow-call-duration-threshold))

          slow-call-rate-threshold
          (.slowCallRateThreshold slow-call-rate-threshold)

          ignore-exceptions
          (.ignoreExceptions (into-array Class ignore-exceptions))

          record-exceptions
          (.recordExceptions (into-array Class record-exceptions))

          ignore-exception
          (.ignoreException (util/fn->predicate ignore-exception))

          record-exception
          (.recordException (util/fn->predicate record-exception)))

        (.build))))

(defn add-listeners
  "Add event handlers for CircuitBreaker lifecycle events. Note that a call that succeeds on the first
   try will generate no events.

   Config map options
     - `:on-event` - a handler that runs for all events
     - `:on-success` - a handler that runs after every successful call - receives a CircuitBreakerOnSuccessEvent
     - `:on-error` - a handler that runs after every failed call - receives a CircuitBreakerOnErrorEvent
     - `:on-state`-transition - a handler that runs after a state changed (OPEN, CLOSED, HALF_OPEN) - receives a CircuitBreakerOnStateTransitionEvent - breakers start in CLOSED
     - `:on-reset` - a handler that runs after a manual reset - receives a CircuitBreakerOnResetEvent
     - `:on-ignored-error` - a handler that runs after an error was ignored - receives a CircuitBreakerOnIgnoredErrorEvent
     - `:on-call-not-permitted` - a handler that runs after a call was attempted, but the breaker forbid it - receives a CircuitBreakerOnCallNotPermittedEvent - NB: this can trigger on health checks, see [[call-allowed?]] for more
     - `:on-failure-rate-exceeded` - a handler that runs after the failure rate is exceeded - receives a CircuitBreakerOnFailureRateExceededEvent
     - `:on-slow-call-rate-exceeded` - a handler that runs after the slow call rate is exceeded - receives a CircuitBreakerOnSlowCallRateExceededEvent - NB: this can trigger on health checks, see [[call-allowed?]] for more
     "
  [^CircuitBreaker cb {:keys [on-event on-success on-error on-state-transition on-reset
                              on-ignored-error on-call-not-permitted on-failure-rate-exceeded on-slow-call-rate-exceeded]}]
  (let [ep (.getEventPublisher cb)]
    ;; Do not try this with cond-> because onEvent returns null
    (when on-event (.onEvent ep (util/fn->event-consumer on-event)))
    (when on-success (.onSuccess ep (util/fn->event-consumer on-success)))
    (when on-error (.onError ep (util/fn->event-consumer on-error)))
    (when on-state-transition (.onStateTransition ep (util/fn->event-consumer on-state-transition)))
    (when on-reset (.onReset ep (util/fn->event-consumer on-reset)))
    (when on-ignored-error (.onIgnoredError ep (util/fn->event-consumer on-ignored-error)))
    (when on-call-not-permitted (.onCallNotPermitted ep (util/fn->event-consumer on-call-not-permitted)))
    (when on-failure-rate-exceeded (.onFailureRateExceeded ep (util/fn->event-consumer on-call-not-permitted)))
    (when on-slow-call-rate-exceeded (.onSlowCallRateExceeded ep (util/fn->event-consumer on-call-not-permitted)))))

(defn circuit-breaker
  "Creates a CircuitBreaker with the given name and config."
  ^CircuitBreaker
  [^String cb-name config]
  (doto (CircuitBreaker/of cb-name (circuit-breaker-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a circuit breaker from a wrapped fn"
  ^CircuitBreaker
  [f]
  (-> f meta :truegrit/circuit-breaker))

(defn state-name
  "Returns a keyword of the current state of the CircuitBreaker.

   One of: `:closed`, `:open`, `:half-open`, `:forced-open`, or `:disabled`

   NB: Do not rely on the state to determine if a call can be made. Use [[call-allowed?]] instead."
  [^CircuitBreaker cb]
  (condp = (.getState cb)
    CircuitBreaker$State/CLOSED :closed
    CircuitBreaker$State/OPEN :open
    CircuitBreaker$State/HALF_OPEN :half-open
    CircuitBreaker$State/DISABLED :disabled
    CircuitBreaker$State/FORCED_OPEN :forced-open
    CircuitBreaker$State/METRICS_ONLY :metrics-only))

(defn call-allowed?
  "Is the circuit breaker healthy enough to allow a call?

   Does not rely on the CB state (OPEN, CLOSED, etc), because state alone is insufficient to determine if
   a call is permitted.

   E.g., HALF-OPEN has an internal counter of test calls, so it may or may not allow the next one. And,
   when `:automatic-transition-from-open-to-half-open-enabled?` is false (the default), the breaker will
   remain in the OPEN state past the waiting period until a call is made or permission is checked/requested,
   which can be misleading.

   NB: r4j does not currently distinguish between checking and acquiring permissions. Therefore, calling
   this when it's *not* currently allowed will trigger a call-not-permitted event, even if you're not
   invoking the wrapped fn."
  [^CircuitBreaker cb]
  (let [allowed (.tryAcquirePermission cb)]
    (.releasePermission cb)
    allowed))

(defn metrics
  "Returns metrics for the given circuit breaker.

   Failure rate is a percentage, unless insufficient calls have been made (i.e.,
   below `:minimum-number-of-calls`), in which case it's -1.

   Slow calls refer to calls that take a long time, but do not actually fail."
  [^CircuitBreaker cb]
  (let [cb-metrics (.getMetrics cb)]
    {:failure-rate                    (.getFailureRate cb-metrics)
     :number-of-buffered-calls        (.getNumberOfBufferedCalls cb-metrics)
     :number-of-failed-calls          (.getNumberOfFailedCalls cb-metrics)
     :number-of-not-permitted-calls   (.getNumberOfNotPermittedCalls cb-metrics)
     :number-of-slow-calls            (.getNumberOfSlowCalls cb-metrics)
     :number-of-slow-failed-calls     (.getNumberOfSlowFailedCalls cb-metrics)
     :number-of-slow-successful-calls (.getNumberOfSlowSuccessfulCalls cb-metrics)
     :number-of-successful-calls      (.getNumberOfSuccessfulCalls cb-metrics)
     :slow-call-rate                  (.getSlowCallRate cb-metrics)}))

(defn wrap
  "Wraps a function in a CircuitBreaker.

   Attaches the circuit breaker as metadata on the wrapped fn at :truegrit/circuit-breaker"
  [f ^CircuitBreaker cb]
  (-> (fn [& args]
        (let [cb-name (.getName cb)
              callable (apply util/fn->callable f args)
              cb-callable (CircuitBreaker/decorateCallable cb callable)]
          (try
            (.call cb-callable)

            (catch CallNotPermittedException e
              (log/error e (str "Call not permitted for CircuitBreaker: " cb-name))
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/circuit-breaker cb))))

