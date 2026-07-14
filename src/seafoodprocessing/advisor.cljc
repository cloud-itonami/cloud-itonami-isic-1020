(ns seafoodprocessing.advisor
  "Seafood Processing Advisor -- the LLM node that drafts proposals.")

(defprotocol Advisor
  (-advise [advisor store request]
    "Advise on a request. Returns a proposal map with :effect, :value, :cites, :confidence, :summary."))

(defn trace
  "Audit trail for an advisor proposal."
  [request proposal]
  {:op (:op request)
   :subject (:subject request)
   :confidence (:confidence proposal)
   :summary (:summary proposal)})

(deftype MockAdvisor [decision-fn]
  Advisor
  (-advise [_ store request]
    (decision-fn store request)))

(defn mock-advisor
  "Create a mock advisor that always returns a canned clean proposal."
  []
  (MockAdvisor.
   (fn [_store request]
     (let [op (:op request)
           subject (:subject request)]
       {:op op
        :subject subject
        :effect :propose
        :value (case op
                 :log-production-batch
                 {:batch-id subject
                  :jurisdiction :us-fda
                  :disposition "ready-to-log"}

                 :coordinate-shipment
                 {:batch-id subject
                  :destination "distribution-center"
                  :carrier "refrigerated-truck"}

                 :schedule-maintenance
                 {:equipment-id (:equipment-id request)
                  :maintenance-type :preventive
                  :scheduled-for "2026-07-15"}

                 :flag-food-safety-concern
                 {:batch-id subject
                  :concern-type :histamine-alert
                  :severity :high}

                 {})
        :cites ["ISIC-1020-operational-baseline"]
        :confidence 0.75
        :summary (str "Proposed: " (name op) " for " subject)}))))

(defn deterministic-advisor
  "Create a mock advisor with explicit proposal overrides for testing."
  [proposal-map]
  (MockAdvisor.
   (fn [_store request]
     (let [sig (select-keys request [:op :subject])]
       (get proposal-map sig
            {:op (:op request)
             :effect :hold
             :value {}
             :cites []
             :confidence 0.0
             :summary "No decision"})))))
