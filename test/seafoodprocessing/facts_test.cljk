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

;; ───────── Verified primary-source citations (2026-07-25) ─────────

(deftest cited-jurisdictions-rest-on-a-fetched-source
  (is (facts/jurisdiction-cited? :eu))
  (is (facts/jurisdiction-cited? :us-fda))
  (is (facts/jurisdiction-cited? :eu-fsanz) "the deprecated alias resolves to :eu")
  (is (not (facts/jurisdiction-cited? :atlantis)))
  (is (nil? (facts/authority-for :atlantis))))

(deftest citation-coverage-names-the-uncited-jurisdictions
  (let [c (facts/citation-coverage)]
    (is (= 6 (:jurisdictions c)))
    (is (= 3 (:cited c)) ":eu, :eu-fsanz (alias) and :us-fda")
    (is (= [:au-daff :jp-mhlw :sg-avs] (:uncited-jurisdictions c))
        "limits without a fetched citation are reported as a gap, not as covered")))

(deftest eu-authority-is-not-fsanz
  (testing "FSANZ is Food Standards Australia New Zealand, not an EU body"
    (is (= :eu (facts/canonical-jurisdiction :eu-fsanz)))
    (is (= {:eu-fsanz :eu} facts/deprecated-jurisdiction-aliases))
    (is (re-find #"European Commission" (:authority (facts/authority-for :eu))))))

(deftest eu-histamine-limits-match-reg-2073-2005
  (let [limits (:statutory-limits (facts/authority-for :eu))]
    (is (= 100.0 (:histamine-m-mg-per-kg limits)) "Annex I cat. 1.26 m")
    (is (= 200.0 (:histamine-M-mg-per-kg limits)) "Annex I cat. 1.26 M")
    (is (= 200.0 (:histamine-brine-m-mg-per-kg limits)) "cat. 1.27 m")
    (is (= 400.0 (:histamine-brine-M-mg-per-kg limits)) "cat. 1.27 M")
    (is (= -18.0 (:frozen-transport-max-temp-c limits)) "853/2004 Annex III")))

(deftest us-histamine-figure-is-guidance-not-regulation
  (let [limits (:statutory-limits (facts/authority-for :us-fda))]
    (is (false? (:histamine-numeric-limit-in-cfr? limits))
        "21 CFR 123 mandates HACCP but states no numeric histamine limit")
    (is (false? (:histamine-guidance-is-regulation? limits)))
    (is (= 50.0 (:histamine-guidance-decomposition-ppm limits)))))

(deftest histamine-units-are-unambiguous
  (testing "limits are mg/100g; the mg/kg table is exactly 10x"
    (is (= 10.0 (:eu facts/histamine-limits)))
    (is (= 100.0 (:eu facts/histamine-limits-mg-per-kg))
        "10 mg/100g == 100 mg/kg == the verified Reg. 2073/2005 m value"))

  (testing "the two entry points agree on the same physical batch"
    ;; 8 mg/100g == 80 ppm: under the EU limit either way.
    (is (facts/histamine-pass? :eu 8.0))
    (is (facts/histamine-pass-ppm? :eu 80.0))
    ;; 12 mg/100g == 120 ppm: over the EU limit either way.
    (is (not (facts/histamine-pass? :eu 12.0)))
    (is (not (facts/histamine-pass-ppm? :eu 120.0))))

  (testing "unknown jurisdiction never passes"
    (is (not (facts/histamine-pass? :atlantis 0.0)))
    (is (not (facts/histamine-pass-ppm? :atlantis 0.0)))))

(deftest unknown-jurisdiction-fails-the-evidence-gate-closed
  (testing "an unknown jurisdiction used to clear the gate vacuously"
    ;; Empty set is a subset of everything, so the old implementation
    ;; returned true here -- with NO evidence supplied at all.
    (is (nil? (facts/required-evidence :atlantis)))
    (is (false? (facts/required-evidence-satisfied? :atlantis {}))
        "must fail closed: the Governor only checks jurisdiction non-nil")
    (is (false? (facts/required-evidence-satisfied? :atlantis
                                                    {:temperature-log true})))))

(deftest eu-key-is-not-vacuous
  (testing ":eu carries real requirements, not an empty set"
    (is (seq (facts/required-evidence :eu)))
    (is (= (facts/required-evidence :eu) (facts/required-evidence :eu-fsanz)))
    (is (false? (facts/required-evidence-satisfied? :eu {:temperature-log true})))))

(deftest frozen-window-gap-vs-eu-transit-limit-is-machine-checkable
  (testing "the -12 degC product max is looser than 853/2004's -18 degC"
    (is (false? (facts/frozen-window-meets-eu-transit-limit? :salmon-frozen)))
    (is (false? (facts/frozen-window-meets-eu-transit-limit? :squid-frozen))))
  (testing "the transit rule does not apply to chilled/ambient products"
    (is (nil? (facts/frozen-window-meets-eu-transit-limit? :live-shrimp)))
    (is (nil? (facts/frozen-window-meets-eu-transit-limit? :canned-tuna)))))
