(ns seafoodprocessing.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [seafoodprocessing.facts :as facts]))

(deftest test-product-catalog
  (testing "catalog has expected products"
    (is (seq facts/product-catalog))
    (is (some #(= :salmon-frozen (:id %)) facts/product-catalog)))

  (testing "product-type-by-id lookups work"
    (let [salmon (facts/product-type-by-id :salmon-frozen)]
      (is (= :salmon-frozen (:id salmon)))
      (is (= -18.0 (:cold-chain-temp-min-c salmon)))
      (is (= :finfish (:category salmon))))

    (let [shrimp (facts/product-type-by-id :live-shrimp)]
      (is (= :live-shrimp (:id shrimp)))
      (is (= 7 (:shelf-life-days-at-min-temp shrimp))))))

(deftest test-jurisdiction-validation
  (testing "valid jurisdictions"
    (is (facts/valid-jurisdiction? :us-fda))
    (is (facts/valid-jurisdiction? :eu-fsanz))
    (is (facts/valid-jurisdiction? :jp-mhlw)))

  (testing "invalid jurisdictions"
    (is (not (facts/valid-jurisdiction? :bogus-country)))))

(deftest test-evidence-requirements
  (testing "US FDA evidence"
    (let [required (facts/required-evidence :us-fda)]
      (is (contains? required :batch-assay))
      (is (contains? required :temperature-log))
      (is (contains? required :inspection-histamine))))

  (testing "evidence satisfaction check"
    (let [complete-checklist {:batch-assay :ok
                              :temperature-log :ok
                              :holding-time-record :ok
                              :inspection-sensory :ok
                              :inspection-histamine :ok
                              :sanitation-certificate :ok
                              :metal-detector-pass :ok}]
      (is (facts/required-evidence-satisfied? :us-fda complete-checklist)))

    (let [incomplete-checklist {:batch-assay :ok
                                :temperature-log :ok}]
      (is (not (facts/required-evidence-satisfied? :us-fda incomplete-checklist))))))

(deftest test-histamine-limits
  (testing "histamine pass/fail"
    (is (facts/histamine-pass? :us-fda 3.0))
    (is (facts/histamine-pass? :us-fda 5.0))
    (is (not (facts/histamine-pass? :us-fda 6.0)))

    (is (facts/histamine-pass? :eu-fsanz 10.0))
    (is (not (facts/histamine-pass? :eu-fsanz 11.0)))))

(deftest test-sanitation-floor
  (testing "sanitation acceptability"
    (is (facts/sanitation-acceptable? 80))
    (is (facts/sanitation-acceptable? 70))
    (is (not (facts/sanitation-acceptable? 69)))))

(deftest test-shelf-life
  (testing "within shelf-life"
    (is (facts/within-shelf-life? :salmon-frozen 200))
    (is (facts/within-shelf-life? :salmon-frozen 365))
    (is (not (facts/within-shelf-life? :salmon-frozen 366))))

  (testing "live shrimp short shelf-life"
    (is (facts/within-shelf-life? :live-shrimp 5))
    (is (not (facts/within-shelf-life? :live-shrimp 10)))))

(deftest test-holding-time-limits
  (testing "holding time acceptable at various temps"
    (is (facts/holding-time-acceptable? 0.0 48))
    (is (facts/holding-time-acceptable? 4.0 48))
    (is (not (facts/holding-time-acceptable? 4.0 49)))

    (is (facts/holding-time-acceptable? 10.0 12))
    (is (not (facts/holding-time-acceptable? 10.0 13)))

    (is (facts/holding-time-acceptable? 20.0 4))
    (is (not (facts/holding-time-acceptable? 20.0 5)))))

;; ─────── Downstream Cross-Actor Handoff (optional, isic-1020 -> isic-1075) ───────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-1020"
   :handoff/batch-id "batch-1"
   :handoff/product-type-id :salmon-frozen
   :handoff/quantity-kg 500.0
   :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"})

(deftest test-handoff-record-well-formed
  (testing "complete handoff passes"
    (is (true? (facts/handoff-record-well-formed? well-formed-handoff))))

  (testing "missing :handoff/quantity-kg fails"
    (is (false? (facts/handoff-record-well-formed? (dissoc well-formed-handoff :handoff/quantity-kg)))))

  (testing "non-positive quantity fails"
    (is (false? (facts/handoff-record-well-formed? (assoc well-formed-handoff :handoff/quantity-kg 0)))))

  (testing "nil handoff fails"
    (is (false? (facts/handoff-record-well-formed? nil)))))
