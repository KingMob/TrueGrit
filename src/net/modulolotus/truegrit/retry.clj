(ns net.modulolotus.truegrit.retry
  "Implements retries, which call a fn again when it fails (up to a limit).

   See https://resilience4j.readme.io/docs/retry"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.retry Retry RetryConfig)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(defn retry-config
  "Creates a Resilience4j RetryConfig.

   Config map options
    - `:max-attempts` - # of times to try - defaults to 3
    - `:wait-duration` - how long to wait after failure before trying a call again - defaults to 500 ms - accepts number of ms or java.time.Duration
    - `:interval-function` - either a 1-arity fn that takes in the number of attempts so far (from 1 to n) and returns the number of ms to wait,
           or an instance of io.github.resilience4j.core.IntervalFunction (see IntervalFunction for useful fns that build common wait strategies
           like exponential backoff)

   Less common config map options
    - `:ignore-exceptions` - a coll of Throwables to ignore - e.g., [IrrelevantException IgnoreThisException] - (includes subclasses of the Throwables, too)
    - `:retry-exceptions` - a coll of Throwables to retry on - defaults to all - `:ignore-exceptions` takes precedence over this
    - `:retry-on-exception` - a 1-arg fn that tests a Throwable and returns true if it should be retried
    - `:retry-on-result` - a 1-arg fn that tests the result and returns true if it should be retried
    - `:fail-after-max-attempts?` - Should it throw a MaxRetriesExceededException if it reached the maximum number of attempts? - defaults to false
    - `:interval-bi-function` - a 2-arity fn that takes in the number of attempts so far (from 1 to n) and either a Throwable or a result - returns
           the number of ms to wait - should only be used when `:retry-on-result` is also set

   WARNING: `:wait-duration`, `:interval-function`, and `:interval-bi-function` conflict. Trying to set more than one will throw an exception.
"
  ^RetryConfig
  [config]
  (let [{:keys [max-attempts
                wait-duration
                interval-function
                ignore-exceptions
                retry-exceptions
                retry-on-exception
                retry-on-result
                fail-after-max-attempts?
                interval-bi-function]} (merge *default-config* config)]
    (-> (RetryConfig/custom)
        (cond->
          max-attempts
          (.maxAttempts max-attempts)

          ;; wait-duration and interval-function conflict
          ;; By placing interval-function after wait-duration, it has precedence
          wait-duration
          (.waitDuration (util/ms-duration wait-duration))

          interval-function
          (.intervalFunction (util/fn->interval-function interval-function))

          ignore-exceptions
          (.ignoreExceptions (into-array Class ignore-exceptions))

          retry-exceptions
          (.retryExceptions (into-array Class retry-exceptions))

          retry-on-exception
          (.retryOnException (util/fn->predicate retry-on-exception))

          retry-on-result
          (.retryOnResult (util/fn->predicate retry-on-result))

          fail-after-max-attempts?
          (.failAfterMaxAttempts fail-after-max-attempts?)

          interval-bi-function
          (.intervalBiFunction (util/fn->interval-bi-function interval-bi-function)))
        (.build))))

(defn add-listeners
  "Add event handlers for Retry lifecycle events. Note that a call that succeeds on the first
   try will generate no events.

   Config map options:
    - `:on-event` - a handler that runs for all events
    - `:on-retry` - a handler that runs after a retry - receives a RetryOnRetryEvent
    - `:on-success` - a handler that runs after a successful retry (NOT a successful initial call) - receives a RetryOnSuccessEvent
    - `:on-error` - a handler that runs after an error and there are no retries left - receives a RetryOnErrorEvent
    - `:on-ignored-error` - a handler that runs after an error was ignored - receives a RetryOnIgnoredErrorEvent"
  [^Retry rt {:keys [on-event on-retry on-success on-error on-ignored-error]}]
  (let [ep (.getEventPublisher rt)]
    ;; Do not try this with cond-> because onEvent returns null
    (when on-event (.onEvent ep (util/fn->event-consumer on-event)))
    (when on-success (.onSuccess ep (util/fn->event-consumer on-success)))
    (when on-retry (.onRetry ep (util/fn->event-consumer on-retry)))
    (when on-error (.onError ep (util/fn->event-consumer on-error)))
    (when on-ignored-error (.onIgnoredError ep (util/fn->event-consumer on-ignored-error)))))

(defn retry
  "Creates a Retry with the given name and config."
  ^Retry
  [^String retry-name config]
  (doto (Retry/of retry-name (retry-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a retry from a wrapped fn"
  ^Retry
  [f]
  (-> f meta :truegrit/retry))

(defn metrics
  "Returns metrics for the given retry.

   Numbers are for overall success of a call, not the underlying number of calls.
   E.g., if it's set to retry up to 5 times, and a call fails 4 times and succeeds
   on the last, that only increments `:number-of-successful-calls-with-retry-attempt`
   by 1."
  [^Retry rt]
  (let [rt-metrics (.getMetrics rt)]
    {:number-of-successful-calls-without-retry-attempt (.getNumberOfSuccessfulCallsWithoutRetryAttempt rt-metrics)
     :number-of-failed-calls-without-retry-attempt     (.getNumberOfFailedCallsWithoutRetryAttempt rt-metrics)
     :number-of-successful-calls-with-retry-attempt    (.getNumberOfSuccessfulCallsWithRetryAttempt rt-metrics)
     :number-of-failed-calls-with-retry-attempt        (.getNumberOfFailedCallsWithRetryAttempt rt-metrics)}))

(defn wrap
  "Wraps a function in a Retry. If the max # of retries is exceeded, throws the last Exception received,
   or MaxRetriesExceeded if errors didn't involve Exceptions.

   Attaches the retry as metadata on the wrapped fn at :truegrit/retry"
  [f ^Retry rt]
  (-> (fn [& args]
        (let [callable (apply util/fn->callable f args)
              rt-callable (Retry/decorateCallable rt callable)]
          (try
            (.call rt-callable)

            (catch Exception e
              (log/debug e (str (.getMessage e) " - (All retries exceeded for Retry: " (some-> rt (.getName)) ")"))
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/retry rt))))
