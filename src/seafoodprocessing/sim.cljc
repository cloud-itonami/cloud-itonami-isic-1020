(ns seafoodprocessing.sim
  "Simulation harness: run operations end-to-end for testing/demo,
  driving the REAL compiled `seafoodprocessing.operation/build`
  StateGraph via `langgraph.graph/run*` -- not a hand-rolled pipeline
  call."
  (:require [clojure.pprint]
            [langgraph.graph :as g]
            [seafoodprocessing.advisor :as advisor]
            [seafoodprocessing.operation :as operation]
            [seafoodprocessing.store :as store]))

(defn demo-run []
  "Run a demo scenario: batch intake -> temperature check -> holding time check -> escalate"
  (let [batch-data {:batch-id "batch-salmon-001"
                    :product-type :salmon-frozen
                    :jurisdiction :us-fda
                    :batch-temp-c -16.0
                    :holding-hours 12
                    :storage-days 45
                    :histamine-ppm 2.5
                    :sanitation-score 85
                    :metal-detector-pass? true
                    :contamination-flag? false
                    :evidence-checklist {:batch-assay :histamine-ppm
                                        :temperature-log :required
                                        :holding-time-record :required
                                        :inspection-sensory :required
                                        :inspection-histamine :required
                                        :sanitation-certificate :required
                                        :metal-detector-pass :required}}
        initial-store (store/mem-store-with-batches {"batch-salmon-001" batch-data})
        actor (operation/build initial-store {:advisor (advisor/mock-advisor)})
        request {:op :log-production-batch
                 :subject "batch-salmon-001"}
        context {:actor-id "seafood-processor-1"
                 :phase :phase-2}
        result (g/run* actor {:request request :context context}
                       {:thread-id "demo-run"})
        state (:state result)]

    (println "=== Seafood Processing Simulation ===")
    (println "Batch: batch-salmon-001 (Salmon frozen)")
    (println "Operation: :log-production-batch")
    (println "Graph status:" (:status result))
    (println "Disposition:" (:disposition state))
    (when (:record state)
      (println "Record to commit:")
      (clojure.pprint/pprint (:record state)))
    (println "Ledger:")
    (clojure.pprint/pprint (store/ledger initial-store))))

(defn -main [& _args]
  (demo-run))
