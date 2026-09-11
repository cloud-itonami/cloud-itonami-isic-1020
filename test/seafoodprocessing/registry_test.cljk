(ns seafoodprocessing.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.registry :as registry]))

(deftest test-batch-temp-out-of-range
  (testing "temperature in range"
    (is (not (registry/batch-temp-out-of-range? -16.0 -18.0 -12.0))))

  (testing "temperature too warm"
    (is (registry/batch-temp-out-of-range? -10.0 -18.0 -12.0)))

  (testing "temperature too cold"
    (is (registry/batch-temp-out-of-range? -20.0 -18.0 -12.0))))

(deftest test-histamine-exceeds-limit
  (testing "histamine within limit"
    (is (not (registry/histamine-exceeds-limit? :us-fda 3.0)))
    (is (not (registry/histamine-exceeds-limit? :us-fda 5.0))))

  (testing "histamine exceeds limit"
    (is (registry/histamine-exceeds-limit? :us-fda 5.1))
    (is (registry/histamine-exceeds-limit? :eu-fsanz 10.1))))

(deftest test-sanitation-below-floor
  (testing "sanitation passing"
    (is (not (registry/sanitation-below-floor? 70)))
    (is (not (registry/sanitation-below-floor? 85))))

  (testing "sanitation failing"
    (is (registry/sanitation-below-floor? 69))
    (is (registry/sanitation-below-floor? 50))))

(deftest test-holding-time-exceeded
  (testing "holding time within safe window"
    (is (not (registry/holding-time-exceeded? 0.0 24)))
    (is (not (registry/holding-time-exceeded? 4.0 48))))

  (testing "holding time exceeded"
    (is (registry/holding-time-exceeded? 4.0 49))
    (is (registry/holding-time-exceeded? 15.0 13))))

(deftest test-shelf-life-expired
  (testing "shelf life not expired"
    (is (not (registry/shelf-life-expired? :salmon-frozen 200))))

  (testing "shelf life expired"
    (is (registry/shelf-life-expired? :salmon-frozen 400))))

(deftest test-batch-safe-to-commit
  (testing "clean batch passes all checks"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -16.0
                 :histamine-ppm 3.0
                 :sanitation-score 80
                 :holding-hours 12
                 :storage-days 100}
          result (registry/batch-safe-to-commit? batch :us-fda)]
      (is (:safe? result))
      (is (empty? (:failures result)))))

  (testing "batch with temp violation"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -5.0
                 :histamine-ppm 3.0
                 :sanitation-score 80}
          result (registry/batch-safe-to-commit? batch :us-fda)]
      (is (not (:safe? result)))
      (is (contains? (set (:failures result)) :temp-out-of-range))))

  (testing "batch with histamine violation"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -16.0
                 :histamine-ppm 6.0
                 :sanitation-score 80}
          result (registry/batch-safe-to-commit? batch :us-fda)]
      (is (not (:safe? result)))
      (is (contains? (set (:failures result)) :histamine-high))))

  (testing "batch with sanitation violation"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -16.0
                 :histamine-ppm 3.0
                 :sanitation-score 50}
          result (registry/batch-safe-to-commit? batch :us-fda)]
      (is (not (:safe? result)))
      (is (contains? (set (:failures result)) :sanitation-low)))))
