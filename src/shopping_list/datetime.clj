(ns shopping-list.datetime
  (:import
   (java.time Instant)))

(defn now []
  (Instant/now))

(defn before [t1 t2]
  (.before t1 t2))

(defn after [t1 t2]
  (.after t1 t2))
