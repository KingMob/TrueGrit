(ns net.modulolotus.truegrit.thread-pool-bulkhead
  "Implements thread-pool-based bulkheads. Due to tricky interactions with Clojure
   thread expectations, consider the default semaphore-based bulkhead instead, and
   take care when using.

   See https://resilience4j.readme.io/docs/bulkhead"
  (:require [clojure.tools.logging.readable :as log]
            [net.modulolotus.truegrit.util :as util])
  (:import (io.github.resilience4j.bulkhead BulkheadFullException ThreadPoolBulkhead ThreadPoolBulkheadConfig)
           (java.util.concurrent CompletionStage)))

(def ^:dynamic *default-config* "Set this to override the R4j defaults with your own" {})

(defn tp-bulkhead-config
  "Creates a Resilience4j ThreadPoolBulkheadConfig.

   Config map options:
    - `:max-thread-pool-size` - max allowed number of threads - defaults to # of processors
    - `:core-thread-pool-size` - default number of threads - defaults to (# of processors - 1)
    - `:queue-capacity` - incoming task queue capacity - defaults to 100
    - `:keep-alive-duration` - when the # of threads exceeds the core size and they've been idle
        for this many milliseconds, they will be stopped - defaults to 20 ms - accepts number of ms or java.time.Duration

   See https://resilience4j.readme.io/docs/bulkhead"
  ^ThreadPoolBulkheadConfig
  [config]
  (let [{:keys [max-thread-pool-size
                core-thread-pool-size
                queue-capacity
                keep-alive-duration]} (merge *default-config* config)]
    (-> (ThreadPoolBulkheadConfig/custom)
        (cond->
          max-thread-pool-size
          (.maxThreadPoolSize max-thread-pool-size)

          core-thread-pool-size
          (.coreThreadPoolSize core-thread-pool-size)

          queue-capacity
          (.queueCapacity queue-capacity)

          keep-alive-duration
          (.keepAliveDuration (util/ms-duration keep-alive-duration)))
        (.build))))

(defn add-listeners
  "Add event handlers for ThreadPoolBulkhead lifecycle events.

   Config map options
    - `:on-event` - a handler that runs for all events
    - `:on-call-rejected` - a handler that runs when a call was rejected - receives a BulkheadOnCallRejectedEvent
    - `:on-call-permitted` - a handler that runs when a call was permitted - receives a BulkheadOnCallPermittedEvent
    - `:on-call-finished` - a handler that runs when a call finishes - receives a BulkheadOnCallFinishedEvent"
  [^ThreadPoolBulkhead tp {:keys [on-event on-call-rejected on-call-permitted on-call-finished]}]
  (let [ep (.getEventPublisher tp)]
    ;; Do not try this with cond-> because onEvent returns null
    (when on-event (.onEvent ep (util/fn->event-consumer on-event)))
    (when on-call-rejected (.onCallRejected ep (util/fn->event-consumer on-call-rejected)))
    (when on-call-permitted (.onCallPermitted ep (util/fn->event-consumer on-call-permitted)))
    (when on-call-finished (.onCallFinished ep (util/fn->event-consumer on-call-finished)))))

(defn bulkhead
  "Creates a ThreadPoolBulkhead with the given name and config.

   WARNING: This does not understand Clojure thread frames, and will not convey dynamic vars to the new threads.
   In short, `binding` and related fns/macros will not work by default. Either use `bound-fn`, or pass in/close
   over what you need.

   NB: No need to await termination when done, it adds a shutdown hook to the runtime."
  ^ThreadPoolBulkhead
  [^String bulkhead-name config]
  (doto (ThreadPoolBulkhead/of bulkhead-name (tp-bulkhead-config config))
        (add-listeners config)))

(defn retrieve
  "Retrieves a tp-bulkhead from a wrapped fn"
  ^ThreadPoolBulkhead
  [f]
  (-> f meta :truegrit/tp-bulkhead))

(defn metrics
  "Returns metrics for the given thread-pool bulkhead."
  [^ThreadPoolBulkhead tp]
  (let [tp-metrics (.getMetrics tp)]
    {:core-thread-pool-size    (.getCoreThreadPoolSize tp-metrics)
     :thread-pool-size         (.getThreadPoolSize tp-metrics)
     :maximum-thread-pool-size (.getMaximumThreadPoolSize tp-metrics)
     :queue-depth              (.getQueueDepth tp-metrics)
     :remaining-queue-capacity (.getRemainingQueueCapacity tp-metrics)
     :queue-capacity           (.getQueueCapacity tp-metrics)}))

(defn wrap
  "Wraps a function in a ThreadPoolBulkhead. Throws BulkheadFullException if full.
   Result is wrapped in a CompletableFuture, which is compatible with `deref`.

   Attaches the thread-pool bulkhead as metadata on the wrapped fn at :truegrit/tp-bulkhead"
  [f ^ThreadPoolBulkhead tp]
  (-> (fn [& args]
        (let [tp-name (.getName tp)
              callable (apply util/fn->callable f args)
              tp-callable (ThreadPoolBulkhead/decorateCallable tp callable)]
          (try
            (.toCompletableFuture ^CompletionStage (.get tp-callable))

            (catch BulkheadFullException e
              (log/debug e (str "Bulkhead full for thread-pool bulkhead: " tp-name))
              (throw e)))))
      (with-meta (assoc (meta f) :truegrit/tp-bulkhead tp))))

