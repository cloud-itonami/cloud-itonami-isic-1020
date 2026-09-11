(ns seafoodprocessing.operation-test
  "Integration tests for `seafoodprocessing.operation/build` -- builds
  the REAL compiled `langgraph.graph` StateGraph and runs it end-to-end
  via `langgraph.graph/run*` through commit / hard-hold / phase-hold /
  escalate-approve / escalate-reject routes. This namespace previously
  called `operation/run-operation`, a plain `let`-threaded advisor ->
  governor -> phase pipeline that never touched `kotoba-lang/langgraph`
  at all. These tests prove the compiled graph is real and that the
  audit ledger (`seafoodprocessing.store/append-ledger!`, newly added
  to `MemStore`) is genuinely wired into the `:commit`/`:hold` nodes --
  falsifiable: the ledger must be empty until a run reaches a terminal
  node, escalated proposals must interrupt (not silently auto-commit),
  and a HARD governor violation must never be reachable through
  `:request-approval`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [seafoodprocessing.operation :as operation]
            [seafoodprocessing.store :as store]))

(def ^:private clean-batch
  {:product-type :salmon-frozen
   :batch-temp-c -16.0
   :holding-hours 12
   :storage-days 45
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
                        :metal-detector-pass :ok}})

(defn- exec [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest commit-path-clean-non-high-stakes-operation
  (testing ":schedule-maintenance is neither high-stakes nor governed by
            any of the batch-metadata HARD checks -- a clean phase-3
            request commits straight through the real compiled graph
            and appends EXACTLY ONE fact to the audit ledger, which was
            empty before the run"
    (let [st (store/mem-store-with-batches {"batch-1" clean-batch})]
      (is (empty? (store/ledger st)) "ledger starts empty")
      (let [actor (operation/build st)
            request {:op :schedule-maintenance :subject "line-3" :equipment-id "line-3"}
            context {:actor-id "processor-1" :phase :phase-3}
            result (exec actor "t-commit" request context)
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (is (some? (:record state)))
        (let [ledger (store/ledger st)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :schedule-maintenance (:op (first ledger))))
          (is (= "line-3" (:subject (first ledger)))))))))

(deftest hard-hold-path-temp-violation
  (testing "a batch temperature outside its product's cold-chain window
            is a HARD, permanent governor violation -- the real graph
            routes straight to :hold (no interrupt, no human-approval
            detour) and durably records the hold fact; the proposal
            NEVER reaches :commit and no :record is ever produced"
    (let [batch (assoc clean-batch :batch-temp-c -5.0)
          st (store/mem-store-with-batches {"batch-1" batch})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-2}
          result (exec actor "t-hold" request context)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (nil? (:record state)))
      (let [ledger (store/ledger st)]
        (is (= 1 (count ledger)))
        (is (= :held (:t (first ledger))))
        (is (seq (:violations (first ledger))))
        (is (some #{:batch-temp-out-of-range} (map :rule (:violations (first ledger)))))))))

(deftest phase-lock-path-testing-phase
  (testing "phase-0 (\"Testing\", :locked? true) HARD-holds every
            operation regardless of Governor cleanliness -- the phase
            gate, not just the Governor, is a genuine hold source"
    (let [st (store/mem-store-with-batches {"batch-1" clean-batch})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-0}
          result (exec actor "t-phase-lock" request context)
          state (:state result)]
      (is (= :hold (:disposition state)))
      (let [ledger (store/ledger st)]
        (is (= 1 (count ledger)))
        (is (= :held (:t (first ledger))))
        (is (= :phase-0 (:phase (first ledger))))
        (is (empty? (:violations (first ledger)))
            "phase-lock hold is distinct from a governor violation -- Governor was clean")))))

(deftest escalate-then-approve-commits
  (testing "a clean, high-stakes :log-production-batch proposal ALWAYS
            escalates -- the real graph GENUINELY interrupts
            (checkpointed) at :request-approval, the ledger stays
            EMPTY while interrupted (not yet committed), and a human
            plant operator's approve! resumes the SAME compiled graph
            and commits via the graph's own :request-approval -> :commit
            edge, durably appending to the ledger with the approver
            recorded"
    (let [st (store/mem-store-with-batches {"batch-1" clean-batch})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-3}
          held (exec actor "t-escalate" request context)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (empty? (store/ledger st)) "not yet committed -- awaiting human sign-off")
      (let [approved (g/run* actor {:approval {:status :approved :by "plant-operator-01"}}
                             {:thread-id "t-escalate" :resume? true})
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:disposition approved-state)))
        (let [ledger (store/ledger st)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-production-batch (:op (first ledger)))))))))

(deftest escalate-then-reject-holds
  (testing "a human plant operator rejecting an escalated proposal
            routes to :hold via the :request-approval node's own
            decision, and durably records the rejection -- not a
            hand-rolled parallel path. The proposal never commits."
    (let [st (store/mem-store-with-batches {"batch-1" clean-batch})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-1"}
          context {:actor-id "processor-1" :phase :phase-3}
          _held (exec actor "t-reject" request context)
          rejected (g/run* actor {:approval {:status :rejected :by "plant-operator-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (is (nil? (:record rejected-state)))
      (let [ledger (store/ledger st)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))
