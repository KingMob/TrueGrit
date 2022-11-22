(ns net.modulolotus.truegrit.util
  "Utility methods for True Grit."
  {:no-doc true}
  (:import (io.github.resilience4j.core EventConsumer IntervalBiFunction IntervalFunction)
           (io.github.resilience4j.core.functions Either)
           (java.time Duration)
           (java.util.function Predicate Supplier)))

(defn fn->callable
  "Given a function and arguments, returns a Callable closure suitable for
   decorating or running on ExecutorServices/ThreadPools.

   (All Clojure fns implement Callable, but only for their 0-arity version)"
  ^Callable
  [f & args]
  (apply partial f args))

(defn fn->supplier
  "Returns a Supplier that returns the result of executing
   a fn on arguments.
  "
  ^Supplier
  [f & args]
  (reify Supplier
    (get [_] (apply f args))))

(defn fn->future-supplier
  "Returns a Supplier that returns a Future containing the result of executing
   the fn on a background thread. Meant for time-limiters.

   Uses `future`, which runs on the soloExecutor thread pool."
  ^Supplier
  [f & args]
  (fn->supplier
    #(future (apply f args))))

(defn fn->predicate
  "Return a Predicate that tests a single input and returns true/false.

   f must be a 1-arg fn that tests the input"
  ^Predicate
  [f]
  (reify Predicate
    (test [_ v]
      (boolean (f v)))))

(defn fn->event-consumer
  "Returns an EventConsumer that receives a single event.

  `f` must be a 1-arg fn"
  ^EventConsumer
  [f]
  (reify EventConsumer
    (consumeEvent [_ event]
      (f event)
      nil)))

(defn fn->interval-function
  "Returns an IntervalFunction that computes a wait interval.

   `f` must be a 1-arity fn that takes in the number of attempts so far, starting with 1.
   `f` may also be a pre-existing IntervalFunction"
  [f]
  (cond
    (fn? f) (reify IntervalFunction
              (apply [_ attempt]
                (long (f attempt))))

    (instance? IntervalFunction f) f
    :else (throw (ex-info (str "Don't know how to convert input " (pr-str f) " to IntervalFunction")
                          {:input f}))))

(defn fn->interval-bi-function
  "Returns an IntervalBiFunction that computes a wait interval.

   `f` must be a 2-arity fn that takes in the number of attempts so far (starting with 1) and either a
   Throwable or a result. `f` may also be a pre-existing IntervalBiFunction"
  [f]
  (cond
    (fn? f) (reify IntervalBiFunction
              ;; Can't seem to get the fucking type hints working on the apply sig
              (apply [_ attempt throwable-or-result]
                (long (f attempt (if (.isLeft ^Either throwable-or-result)
                                   (.getLeft ^Either throwable-or-result)
                                   (.get ^Either throwable-or-result))))))

    (instance? IntervalBiFunction f) f
    :else (throw (ex-info (str "Don't know how to convert input " (pr-str f) " to IntervalFunction")
                          {:input f}))))

(defn ms-duration
  "Convenience method to turn an integer into a ms-based Duration object.

  If the input is already a Duration, returns as is.
  Otherwise, throws an `ex-info`."
  ^Duration
  [d]
  (cond
    (integer? d) (Duration/ofMillis (long d))
    (instance? Duration d) d
    :else (throw (ex-info (str "Don't know how to convert input " (pr-str d) " to Duration")
                          {:input d}))))
