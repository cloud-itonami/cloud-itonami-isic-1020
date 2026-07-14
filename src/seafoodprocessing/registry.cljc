(ns seafoodprocessing.registry
  "Pure food-safety validation functions (no external deps).
  These are the core checks the Governor applies independently
  of the Advisor's confidence."
  (:require [seafoodprocessing.facts :as facts]))

(defn batch-temp-out-of-range?
  "Check if a batch temperature is outside the product's cold-chain window."
  [batch-temp-c min-c max-c]
  (or (< batch-temp-c min-c)
      (> batch-temp-c max-c)))

(defn histamine-exceeds-limit?
  "Check if batch histamine assay exceeds jurisdiction limit.
  Returns true if UNSAFE (exceeds limit)."
  [jurisdiction batch-histamine-ppm]
  (let [limit (get facts/histamine-limits jurisdiction)]
    (when limit
      (> batch-histamine-ppm limit))))

(defn sanitation-below-floor?
  "Check if sanitation score is below acceptable floor."
  [score]
  (< score facts/sanitation-floor))

(defn holding-time-exceeded?
  "Check if holding time at temperature exceeds safe window."
  [storage-temp-c holding-hours]
  (not (facts/holding-time-acceptable? storage-temp-c holding-hours)))

(defn shelf-life-expired?
  "Check if batch has been stored beyond shelf-life for product."
  [product-id storage-days]
  (not (facts/within-shelf-life? product-id storage-days)))

;; Combined: "is this batch safe to log/ship?"
(defn batch-safe-to-commit?
  "Comprehensive check: all food-safety gates pass for batch commit.
  Returns {:safe? boolean, :failures [keywords]}"
  [batch-record jurisdiction]
  (let [product-id (:product-type batch-record)
        product (facts/product-type-by-id product-id)
        failures (cond-> []
                   (not product)
                   (conj :unknown-product)

                   product
                   (cond->
                     (batch-temp-out-of-range?
                      (:batch-temp-c batch-record)
                      (:cold-chain-temp-min-c product)
                      (:cold-chain-temp-max-c product))
                     (conj :temp-out-of-range)

                     (and (:histamine-ppm batch-record)
                          (histamine-exceeds-limit? jurisdiction (:histamine-ppm batch-record)))
                     (conj :histamine-high)

                     (:sanitation-score batch-record)
                     (when (sanitation-below-floor? (:sanitation-score batch-record))
                       (conj :sanitation-low))

                     (and (:holding-hours batch-record)
                          (:batch-temp-c batch-record))
                     (when (holding-time-exceeded? (:batch-temp-c batch-record) (:holding-hours batch-record))
                       (conj :holding-time-exceeded))

                     (and (:storage-days batch-record)
                          product)
                     (when (shelf-life-expired? product-id (:storage-days batch-record))
                       (conj :shelf-life-expired))))]
    {:safe? (empty? failures)
     :failures failures}))
