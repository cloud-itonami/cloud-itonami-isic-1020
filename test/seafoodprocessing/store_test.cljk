(ns seafoodprocessing.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.store :as store]))

(deftest test-mem-store
  (testing "empty store"
    (let [s (store/mem-store)]
      (is (nil? (store/processing-batch s "batch-1")))))

  (testing "add and retrieve batch"
    (let [s (store/mem-store)
          batch-data {:product-type :salmon-frozen :batch-temp-c -16.0}
          s2 (store/add-batch s "batch-1" batch-data)]
      (is (= batch-data (store/processing-batch s2 "batch-1")))))

  (testing "update batch"
    (let [s (store/mem-store)
          batch-data {:product-type :salmon-frozen :batch-temp-c -16.0}
          s2 (store/add-batch s "batch-1" batch-data)
          s3 (store/update-batch s2 "batch-1" #(assoc % :batch-temp-c -14.0))]
      (is (= -14.0 (:batch-temp-c (store/processing-batch s3 "batch-1"))))))

  (testing "resolve contamination flag"
    (let [s (store/mem-store)
          batch-data {:product-type :salmon-frozen :contamination-flag? true}
          s2 (store/add-batch s "batch-1" batch-data)
          s3 (store/resolve-contamination-flag s2 "batch-1")]
      (is (false? (:contamination-flag? (store/processing-batch s3 "batch-1")))))))

(deftest test-mem-store-with-batches
  (testing "pre-populate store"
    (let [batches {"batch-1" {:product-type :salmon-frozen}
                   "batch-2" {:product-type :live-shrimp}}
          s (store/mem-store-with-batches batches)]
      (is (= :salmon-frozen (:product-type (store/processing-batch s "batch-1"))))
      (is (= :live-shrimp (:product-type (store/processing-batch s "batch-2")))))))
