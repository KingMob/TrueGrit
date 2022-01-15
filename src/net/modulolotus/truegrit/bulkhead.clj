(ns net.modulolotus.truegrit.bulkhead
  "Implements semaphore-based bulkheads.

   See https://resilience4j.readme.io/docs/bulkhead"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.bulkhead Bulkhead BulkheadConfig BulkheadFullException)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(defn bulkhead-config
  "Creates a Resilience4j BulkheadConfig.

   Config map options:
    - `:max-concurrent-calls` - max allowed number of simultaneous calls - defaults to 25
    - `:max-wait-duration` - maximum amount of time to block waiting to execute - defaults to 0 ms - accepts number of ms or java.time.Duration
"
  ^BulkheadConfig
  [config]
  (let [{:keys [max-concurrent-calls
                max-wait-duration]} (merge *default-config* config)]
    (-> (BulkheadConfig/custom)
        (cond->
          max-concurrent-calls
          (.maxConcurrentCalls max-concurrent-calls)

          max-wait-duration
          (.maxWaitDuration (util/ms-duration max-wait-duration)))
        (.build))))

(defn add-listeners
  "Add event handlers for Bulkhead lifecycle events.

   Config map options
    - `:on-event` - a handler that runs for all events
    - `:on-call-rejected` - a handler that runs when a call was rejected - receives a BulkheadOnCallRejectedEvent
    - `:on-call-permitted` - a handler that runs when a call was permitted - receives a BulkheadOnCallPermittedEvent
    - `:on-call-finished` - a handler that runs when a call finishes - receives a BulkheadOnCallFinishedEvent"
  [^Bulkhead bh {:keys [on-event on-call-rejected on-call-permitted on-call-finished]}]
  (let [ep (.getEventPublisher bh)]
    ;; Do not try this with cond-> because onEvent returns null
    (when on-event (.onEvent ep (util/fn->event-consumer on-event)))
    (when on-call-rejected (.onCallRejected ep (util/fn->event-consumer on-call-rejected)))
    (when on-call-permitted (.onCallPermitted ep (util/fn->event-consumer on-call-permitted)))
    (when on-call-finished (.onCallFinished ep (util/fn->event-consumer on-call-finished)))))

(defn bulkhead
  "Creates a Bulkhead with the given name and config."
  ^Bulkhead
  [^String bulkhead-name config]
  (doto (Bulkhead/of bulkhead-name (bulkhead-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a bulkhead from a wrapped fn"
  ^Bulkhead
  [f]
  (-> f meta :truegrit/bulkhead))

(defn metrics
  "Returns metrics for the given bulkhead."
  [^Bulkhead bh]
  (let [bh-metrics (.getMetrics bh)]
    {:available-concurrent-calls   (.getAvailableConcurrentCalls bh-metrics)
     :max-allowed-concurrent-calls (.getMaxAllowedConcurrentCalls bh-metrics)}))

(defn wrap
  "Wraps a function in a Bulkhead. Throws BulkheadFullException if full and times out.

   Attaches the bulkhead as metadata on the wrapped fn at :truegrit/bulkhead"
  [f ^Bulkhead bh]
  (-> (fn [& args]
        (let [bh-name (.getName bh)
              callable (apply util/fn->callable f args)
              bh-callable (Bulkhead/decorateCallable bh callable)]
          (try
            (.call bh-callable)

            (catch BulkheadFullException e
              (log/debug e (str "Bulkhead full for bulkhead: " bh-name))
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/bulkhead bh))))
