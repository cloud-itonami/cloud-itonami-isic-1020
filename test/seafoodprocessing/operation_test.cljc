(ns seafoodprocessing.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.operation :as operation]
            [seafoodprocessing.store :as store]
            [seafoodprocessing.advisor :as advisor]))

(deftest test-run-operation-clean-high-stakes-escalate
  (testing "clean batch for high-stakes operation (log-production-batch) escalates in phase-2"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -16.0
                 :holding-hours 12
                 :histamine-ppm 2.5
                 :sanitation-score 85
                 :metal-detector-pass? true
                 :contamination-flag? false
                 :jurisdiction :us-fda
                 :evidence-checklist {:batch-assay :ok
                                     :temperature-log :ok
                                     :holding-time-record :ok
                                     :inspection-sensory :ok
                                     :inspection-histamine :ok
                                     :sanitation-certificate :ok
                                     :metal-detector-pass :ok}}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-2}
          result (operation/run-operation st request context {:advisor (advisor/mock-advisor)})]
      ;; High-stakes operations always escalate to human approval, even when Governor is clean
      (is (= :escalate (:disposition result)))
      (is (nil? (:record result))))))

(deftest test-run-operation-temp-violation
  (testing "temperature violation holds the operation"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -5.0
                 :holding-hours 12
                 :histamine-ppm 2.5
                 :sanitation-score 85
                 :metal-detector-pass? true
                 :contamination-flag? false
                 :jurisdiction :us-fda
                 :evidence-checklist {:batch-assay :ok
                                     :temperature-log :ok
                                     :holding-time-record :ok
                                     :inspection-sensory :ok
                                     :inspection-histamine :ok
                                     :sanitation-certificate :ok
                                     :metal-detector-pass :ok}}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-2}
          result (operation/run-operation st request context {:advisor (advisor/mock-advisor)})]
      (is (= :hold (:disposition result)))
      (is (nil? (:record result))))))

(deftest test-run-operation-high-stakes-escalate
  (testing "high-stakes operation escalates to human approval"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -16.0
                 :holding-hours 12
                 :histamine-ppm 2.5
                 :sanitation-score 85
                 :metal-detector-pass? true
                 :contamination-flag? false
                 :jurisdiction :us-fda
                 :evidence-checklist {:batch-assay :ok
                                     :temperature-log :ok
                                     :holding-time-record :ok
                                     :inspection-sensory :ok
                                     :inspection-histamine :ok
                                     :sanitation-certificate :ok
                                     :metal-detector-pass :ok}}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-3}
          result (operation/run-operation st request context {:advisor (advisor/mock-advisor)})]
      (is (= :escalate (:disposition result)))
      (is (nil? (:record result))))))
