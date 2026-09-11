(ns seafoodprocessing.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.governor :as governor]
            [seafoodprocessing.store :as store]))

(deftest test-high-stakes-operations
  (testing "high-stakes flag identifies actuation operations"
    (is (contains? governor/high-stakes :log-production-batch))
    (is (contains? governor/high-stakes :coordinate-shipment))
    (is (not (contains? governor/high-stakes :schedule-maintenance)))))

(deftest test-governor-no-jurisdiction
  (testing "proposal without jurisdiction is violation"
    (let [request {:op :log-production-batch :subject "batch-1"}
          proposal {:cites [] :value {}}
          st (store/mem-store)
          context {}
          verdict (governor/check request context proposal st)]
      (is (seq (:violations verdict)))
      (is (some #(= :no-spec-basis (:rule %)) (:violations verdict))))))

(deftest test-governor-temp-violation
  (testing "batch out of cold-chain temp range"
    (let [batch {:product-type :salmon-frozen
                 :batch-temp-c -5.0
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
          proposal {:cites ["spec"] :value {:jurisdiction :us-fda}}
          context {}
          verdict (governor/check request context proposal st)]
      (is (seq (:violations verdict)))
      (is (some #(= :batch-temp-out-of-range (:rule %)) (:violations verdict))))))

(deftest test-governor-high-stakes
  (testing "high-stakes operations flagged"
    (let [batch {:product-type :salmon-frozen :batch-temp-c -16.0}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :log-production-batch :subject "batch-1"}
          proposal {:cites ["spec"] :value {:jurisdiction :us-fda}}
          context {}
          verdict (governor/check request context proposal st)]
      (is (:high-stakes? verdict)))))

(deftest test-hold-fact
  (testing "hold fact records governor rejection"
    (let [request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1"}
          verdict {:violations [{:rule :temp-out-of-range}]}
          fact (governor/hold-fact request context verdict)]
      (is (= :held (:t fact)))
      (is (= :hold (:disposition fact)))
      (is (= "batch-1" (:subject fact))))))

;; ─────── Downstream Cross-Actor Handoff (optional, isic-1020 -> isic-1075) ───────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-1020"
   :handoff/batch-id "batch-1"
   :handoff/product-type-id :salmon-frozen
   :handoff/quantity-kg 500.0
   :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"})

(deftest test-handoff-optional-no-violation
  (testing "no :handoff at all -> no violation on an otherwise-clean coordinate-shipment"
    (let [batch {:product-type :salmon-frozen :batch-temp-c -16.0}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :coordinate-shipment :subject "batch-1"}
          proposal {:cites ["spec"] :value {:jurisdiction :us-fda}}
          context {}
          verdict (governor/check request context proposal st)]
      (is (not (some #(= :handoff-malformed (:rule %)) (:violations verdict)))))))

(deftest test-handoff-well-formed-no-violation
  (testing "well-formed :handoff -> no handoff-malformed violation"
    (let [batch {:product-type :salmon-frozen :batch-temp-c -16.0}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :coordinate-shipment :subject "batch-1"}
          proposal {:cites ["spec"] :value {:jurisdiction :us-fda :handoff well-formed-handoff}}
          context {}
          verdict (governor/check request context proposal st)]
      (is (not (some #(= :handoff-malformed (:rule %)) (:violations verdict)))))))

(deftest test-handoff-malformed-is-violation
  (testing "malformed :handoff (missing quantity-kg) -> handoff-malformed violation"
    (let [batch {:product-type :salmon-frozen :batch-temp-c -16.0}
          st (store/mem-store-with-batches {"batch-1" batch})
          request {:op :coordinate-shipment :subject "batch-1"}
          proposal {:cites ["spec"]
                    :value {:jurisdiction :us-fda
                            :handoff (dissoc well-formed-handoff :handoff/quantity-kg)}}
          context {}
          verdict (governor/check request context proposal st)]
      (is (some #(= :handoff-malformed (:rule %)) (:violations verdict))))))
