(ns seafoodprocessing.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.phase :as phase]))

(deftest test-verdict-to-disposition
  (testing "no violations -> commit"
    (let [verdict {:violations [] :high-stakes? false}]
      (is (= :commit (phase/verdict->disposition verdict)))))

  (testing "violations -> hold"
    (let [verdict {:violations [{:rule :temp-out-of-range}] :high-stakes? false}]
      (is (= :hold (phase/verdict->disposition verdict)))))

  (testing "high-stakes -> escalate"
    (let [verdict {:violations [] :high-stakes? true}]
      (is (= :escalate (phase/verdict->disposition verdict))))))

(deftest test-phase-gate
  (testing "phase-0 locked"
    (let [request {:op :flag-food-safety-concern}
          result (phase/gate :phase-0 request :commit)]
      (is (= :hold (:disposition result)))
      (is (string? (:reason result)))))

  (testing "phase-1 allows only flag-concern"
    (let [request {:op :flag-food-safety-concern}
          result (phase/gate :phase-1 request :commit)]
      (is (= :escalate (:disposition result))))

    (let [request {:op :log-production-batch}
          result (phase/gate :phase-1 request :commit)]
      (is (= :hold (:disposition result)))))

  (testing "phase-2 allows flag-concern and log-batch"
    (let [request {:op :log-production-batch}
          result (phase/gate :phase-2 request :commit)]
      (is (= :commit (:disposition result))))

    (let [request {:op :coordinate-shipment}
          result (phase/gate :phase-2 request :commit)]
      (is (= :hold (:disposition result)))))

  (testing "phase-3 allows all operations"
    (let [request {:op :coordinate-shipment}
          result (phase/gate :phase-3 request :commit)]
      (is (= :commit (:disposition result))))))
