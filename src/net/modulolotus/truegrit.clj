(ns net.modulolotus.truegrit
  "Primary namespace for using True Grit.

   Contains all-in-one functions that take a fn and a config map, and return a wrapped fn with the fault
   tolerance policy attached. All wrapped fns return the same results, except for thread-pool-based bulkheads
   which return Futures. This ns is best for simple use cases. For additional fns and more fine-tuned control,
   see the individual policy namespaces.

   Important: Before using, be sure to understand how Resilience4j works, and in particular, the way circuit
   breakers operate.

   For circuit breakers or bulkheads, you should be careful to only create them once. Since those resilience
   policies keep state that is only meaningful across multiple function calls, dynamically wrapping a fn
   with a brand-new strategy by accident is incorrect. E.g., a new CB is *always* good for the first
   n calls, but a pre-existing CB may know that the underlying service is down. With other policies, dynamic
   recreation will result in a lot of churn and memory pressure, but they will still operate as expected.

   Be mindful of interactions at different levels of the system. E.g., wrapping a high-level fn with a retry
   strategy of 3 attempts that calls a network client lower down that also has its _own_ retry strategy of 3
   attempts can result in up to 3x3=9 calls when failing, exacerbating things.

   Order of wrapping matters. E.g.:

   ```clojure
   (-> my-fn
       (with-retry some-retry-config)
       (with-time-limiter some-timeout-config)
   ```

   will retry several times, but if the time is up before a try succeeds, it will return failure. This is
   probably not what you want. On the other hand:

   ```clojure
   (-> my-fn
       (with-time-limiter some-timeout-config)
       (with-retry some-retry-config)
   ```

   will make calls with a certain time limit, and only if they return failure or exceed their time limit, will
   it attempt a retry."
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.bulkhead :as bulkhead]
            [net.modulolotus.truegrit.circuit-breaker :as circuit-breaker]
            [net.modulolotus.truegrit.rate-limiter :as rate-limiter]
            [net.modulolotus.truegrit.retry :as retry]
            [net.modulolotus.truegrit.thread-pool-bulkhead :as tp-bulkhead]
            [net.modulolotus.truegrit.time-limiter :as time-limiter]))

(defn with-time-limiter
  "Wrap a given fn with a timeout. Takes a fn and a config map.
   Attaches the time-limiter as metadata to the returned fn.

   If the function does not return before the time is up, a TimeoutException is thrown.

   Config map options
    - `:cancel-running-future?` - should `.cancel()` be called on the running Future? - defaults to true
    - `:timeout-duration` - how long to wait before timing out - defaults to 1000 ms - accepts number of ms or java.time.Duration

   Event listener config map options
    - `:on-success` - a handler that runs after a successful call - receives a TimeLimiterOnSuccessEvent
    - `:on-error` - a handler that runs after an error - receives a TimeLimiterOnErrorEvent
    - `:on-timeout` - a handler that runs after a call times out - receives a TimeLimiterOnTimeoutEvent"
  [f config]
  (let [tl (time-limiter/time-limiter config)]
    (log/debug "Wrapping with time limit" {:timeout-duration (.getTimeoutDuration (.getTimeLimiterConfig tl))})
    (time-limiter/wrap f tl)))

(defn with-rate-limiter
  "Wrap a given fn with a rate-limiter. Takes a fn and a config map.

   Delays calls as needed to occur below a certain rate. If a call times out waiting to run, throws
   a RequestNotPermittedException.

   Config map options
    - `:limit-for-period` - the number of calls allowed per time window - defaults to 50
    - `:limit-refresh-period` - the Duration of the time window - defaults to 500 nanoseconds - accepts number of milliseconds or java.time.Duration
    - `:timeout-duration` - how long to wait to be allowed to proceed before timing out - defaults to 5000 ms - accepts number of milliseconds or java.time.Duration

   Event listener config map options
    - `:on-success` - a handler that runs after a successful call - receives a RateLimiterOnSuccessEvent
    - `:on-failure` - a handler that runs after an failure - receives a RateLimiterOnFailureEvent"
  [f {rl-name :name :as config}]
  (let [rl (rate-limiter/rate-limiter rl-name config)]
    (log/debug "Wrapping with rate-limiter" {:rate-limiter-name rl-name :config config})
    (rate-limiter/wrap f rl)))


(defn with-retry
  "Wrap a given fn with a retry. Takes a fn and a config map.

   When a call fails, it can be retried (up to a certain # of times), with an optional interval between calls.

   Config map options
    - `:max-attempts` - # of times to try - defaults to 3
    - `:wait-duration` - how long to wait after failure before trying a call again - defaults to 500 ms - accepts number of ms or java.time.Duration
    - `:interval-function` - either a 1-arity fn that takes in the number of attempts so far (from 1 to n) and returns the number of ms to wait,
           or an instance of io.github.resilience4j.core.IntervalFunction (see IntervalFunction for useful fns that build common wait strategies
           like exponential backoff)

   Less common config map options
    - `:ignore-exceptions` - a coll of Throwables to ignore - e.g., `[IrrelevantException IgnoreThisException]` - (includes subclasses of the Throwables, too)
    - `:retry-exceptions` - a coll of Throwables to retry on - defaults to all - `:ignore-exceptions` takes precedence over this
    - `:retry-on-exception` - a 1-arg fn that tests a Throwable and returns true if it should be retried
    - `:retry-on-result` - a 1-arg fn that tests the result and returns true if it should be retried
    - `:fail-after-max-attempts?` - If :retry-on-result is set, should it throw a MaxRetriesExceededException if it reached the maximum number of attempts, but the retry-on-result predicate is still true? - defaults to false
    - `:interval-bi-function` - a 2-arity fn that takes in the number of attempts so far (from 1 to n) and either a Throwable or a result - returns
           the number of ms to wait - should only be used when `:retry-on-result` is also set

   WARNING: `:wait-duration`, `:interval-function`, and `:interval-bi-function` conflict. Trying to set more than one will throw an exception.

   Event listener config map options
    - `:on-event` - a handler that runs for all events
    - `:on-retry` - a handler that runs after a retry - receives a RetryOnRetryEvent
    - `:on-success` - a handler that runs after a successful retry (NOT a successful initial call) - receives a RetryOnSuccessEvent
    - `:on-error` - a handler that runs after an error and there are no retries left - receives a RetryOnErrorEvent
    - `:on-ignored-error` - a handler that runs after an error was ignored - receives a RetryOnIgnoredErrorEvent"
  [f {rt-name :name :as config}]
  (let [rt (retry/retry rt-name config)]
    (log/debug "Wrapping with retry" {:retry-name rt-name :config config})
    (retry/wrap f rt)))

(defn with-circuit-breaker
  "Wrap a given fn with a circuit breaker. Takes a fn and a config map.

   Collects stats on the success/failure rates of recent fn calls. If the failure rate gets too high,
   will temporarily halt all calls. After some time, will start allowing calls again. If a call is not currently
   allowed, a CallNotPermittedException is thrown.

   Config map options
     - `:failure-rate-threshold` - Percentage from 1 to 100 - if more than this percentage of calls have failed, go to OPEN - defaults to 50%
     - `:slow-call-rate-threshold` - Percentage from 1 to 100 - if more than this percentage of calls are too slow, go to OPEN - defaults to 100%
     - `:slow-call-duration-threshold` - How slow is too slow, *even if* the call succeeds? All calls exceeding this duration are treated as failures - defaults to 60000 ms - accepts number of ms or java.time.Duration
     - `:permitted-number-of-calls-in-half-open-state` - # of calls to track when HALF-OPEN - defaults to 10
     - `:minimum-number-of-calls` - # of calls to keep track of - will not go to OPEN unless at least this many calls have been made - defaults to 100
     - `:sliding-window-type` - How the CB considers statistics. Either `:count` or `:time`. When count-based, examines the last n number of calls; when time-based, examines all calls from the last n seconds. (Time-based mimics Hystrix's behavior.) - defaults to `:count`
     - `:sliding-window-size` - The meaning depends on `:sliding-window-type`. When count-based, it's the number of calls to record; when time-based, it's the number of seconds. - defaults to 100
     - `:max-wait-duration-in-half-open-state` - How long to wait in HALF_OPEN before automatically switching to OPEN - 0 means wait until all calls have been completed - defaults to 0 ms - accepts number of ms or java.time.Duration
     - `:wait-duration-in-open-state` - How long to wait in OPEN before allowing a call again - defaults to 60000 ms - accepts number of ms or java.time.Duration

   Less common config map options
     - `:automatic-transition-from-open-to-half-open-enabled?` - Should it automatically transition to HALF-OPEN after the wait duration, or only after a call or check is made? - NB: when false, an OPEN state may actually allow calls if the wait duration has passed and nobody has tried yet, so don't rely on state, use [[truegrit.circuit-breaker/call-allowed?]] - defaults to false
     - `:record-exceptions` - A coll of Throwables to record failure for - defaults to empty, which means all exceptions are recorded - `:ignore-exceptions` takes precedence over this
     - `:ignore-exceptions` - A coll of Throwables to ignore - e.g., `[IrrelevantException IgnoreThisException]` - are not counted as either successes *or* failures - defaults to empty
     - `:record-exception` - A 1-arg fn that tests a Throwable and returns true if it should be recorded as a failure, false if not - `:ignore-exception` predicate takes precedence
     - `:ignore-exception` - A 1-arg fn that tests a Throwable and returns true if it should be ignored, false if not

   Event listener config map options
     - `:on-event` - a handler that runs for all events
     - `:on-success` - a handler that runs after every successful call - receives a CircuitBreakerOnSuccessEvent
     - `:on-error` - a handler that runs after every failed call - receives a CircuitBreakerOnErrorEvent
     - `:on-state-transition` - a handler that runs after a state changed (OPEN, CLOSED, HALF_OPEN) - receives a CircuitBreakerOnStateTransitionEvent - breakers start in CLOSED
     - `:on-reset` - a handler that runs after a manual reset - receives a CircuitBreakerOnResetEvent
     - `:on-ignored-error` - a handler that runs after an error was ignored - receives a CircuitBreakerOnIgnoredErrorEvent
     - `:on-call-not-permitted` - a handler that runs after a call was attempted, but the breaker forbid it - receives a CircuitBreakerOnCallNotPermittedEvent - NB: this can trigger on health checks, see [[truegrit.circuit-breaker/call-allowed?]] for more
     - `:on-failure-rate-exceeded` - a handler that runs after the failure rate is exceeded - receives a CircuitBreakerOnFailureRateExceededEvent
     - `:on-slow-call-rate-exceeded` - a handler that runs after the slow call rate is exceeded - receives a CircuitBreakerOnSlowCallRateExceededEvent - NB: this can trigger on health checks, see [[truegrit.circuit-breaker/call-allowed?]] for more
"
  [f {cb-name :name :as config}]
  (let [cb (circuit-breaker/circuit-breaker cb-name config)]
    (log/debug "Wrapping with circuit breaker" {:cb-name cb-name :config config})
    (circuit-breaker/wrap f cb)))

(defn with-bulkhead
  "Wrap a given fn with a semaphore-based bulkhead. Takes a fn and a config map.

   Used to limit the number of simultaneous threads executing some code. Designed
   for multiple bulkheads per service.

   E.g., creating two bulkheads, one for servicing user requests, and one for batch
   job requests. By splitting the available capacity across the two, we can ensure
   that batch job requests cannot consume all available threads, and always reserve
   some for users.

   Config map options:
    - `:max-concurrent-calls` - max allowed number of simultaneous calls - defaults to 25
    - `:max-wait-duration` - maximum amount of time to block waiting to execute - defaults to 0 ms - accepts number of ms or java.time.Duration

   Event listener config map options
    - `:on-event` - a handler that runs for all events
    - `:on-call-rejected` - a handler that runs when a call was rejected - receives a BulkheadOnCallRejectedEvent
    - `:on-call-permitted` - a handler that runs when a call was permitted - receives a BulkheadOnCallPermittedEvent
    - `:on-call-finished` - a handler that runs when a call finishes - receives a BulkheadOnCallFinishedEvent"
  [f {bh-name :name :as config}]
  (let [bh (bulkhead/bulkhead bh-name config)]
    (log/debug "Wrapping with bulkhead" {:bh-name bh-name :config config})
    (bulkhead/wrap f bh)))

(defn with-thread-pool-bulkhead
  "Wrap a given fn to run on a thread-pool-based bulkhead. Takes a fn and a config map.

   NB: The wrapped fn returns Futures, not values. Deref to obtain the result.

   Similar to regular (semaphore-based) bulkheads, but creates its own thread pool for dispatching.

   Config map options:
    - `:max-thread-pool-size` - max allowed number of threads - defaults to # of processors
    - `:core-thread-pool-size` - default number of threads - defaults to (# of processors - 1)
    - `:queue-capacity` - incoming task queue capacity - defaults to 100
    - `:keep-alive-duration` - when the # of threads exceeds the core size and they've been idle for this many
         milliseconds, they will be stopped - defaults to 20 ms - accepts number of ms or java.time.Duration

   Event listener config map options
    - `:on-event` - a handler that runs for all events
    - `:on-call-rejected` - a handler that runs when a call was rejected - receives a BulkheadOnCallRejectedEvent
    - `:on-call-permitted` - a handler that runs when a call was permitted - receives a BulkheadOnCallPermittedEvent
    - `:on-call-finished` - a handler that runs when a call finishes - receives a BulkheadOnCallFinishedEvent

   WARNING: This does not understand Clojure thread frames, and will not convey dynamic vars to the new threads.
   In short, `binding` and related fns/macros will not work by default. Either use `bound-fn`, or pass in/close
   over what you need.

   NB: No need to await termination when done, it adds a shutdown hook to the runtime."
  [f {tp-name :name :as config}]
  (let [tp (tp-bulkhead/bulkhead tp-name config)]
    (log/debug "Wrapping with thread pool" {:tp-name tp-name :config config})
    (tp-bulkhead/wrap f tp)))


#_ (defn with-resilience
  "Convenience fn that wraps several strategies at once.

  Params
   f - fn to wrap
   coll - a collection of maps "
  [f {:keys [name time-limiter retry circuit-breaker]}]
  (let [name-config (if name {:name name} {})]
    (cond-> f
            time-limiter
            (with-time-limiter (merge name-config time-limiter))

            retry
            (with-retry (merge name-config retry))

            circuit-breaker
            (with-circuit-breaker (merge name-config circuit-breaker)))))




(comment

  (defn robustify
    "Demo fn that wraps common strategies in a preferred order:
     time limiter -> retry -> circuit breaker

     Params
      f - fn to wrap
      :time-limiter, :retry, :circuit-breaker - config maps"
    [f {:keys [time-limiter retry circuit-breaker]}]
    (cond-> f
            time-limiter
            (with-time-limiter time-limiter)

            retry
            (with-retry retry)

            circuit-breaker
            (with-circuit-breaker circuit-breaker)))

  )
