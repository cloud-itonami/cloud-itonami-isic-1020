(ns seafoodprocessing.operation
  "OperationActor -- one seafood-processing operation = one supervised actor run."
  (:require [seafoodprocessing.advisor :as advisor]
            [seafoodprocessing.governor :as governor]
            [seafoodprocessing.phase :as phase]
            [seafoodprocessing.store :as store]))

(defn- commit-fact [request context proposal]
  {:t :committed
   :op (:op request)
   :actor (:actor-id context)
   :subject (:subject request)
   :disposition :commit
   :basis (:cites proposal)
   :summary (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect (:effect proposal)
   :path [(:subject request)]
   :value (or (:value proposal) {})
   :payload (:value proposal)})

(defn run-operation
  "Run one seafood-processing operation through the advisor -> governor -> phase
  gate -> decision flow. Returns a map with :disposition, :audit, :record."
  [store request context & [{:keys [advisor]
                             :or {advisor (advisor/mock-advisor)}}]]
  (let [proposal (advisor/-advise advisor store request)
        advisor-trace (advisor/trace request proposal)
        verdict (governor/check request context proposal store)
        base-disposition (phase/verdict->disposition verdict)
        ph (:phase context phase/default-phase)
        {:keys [disposition reason]} (phase/gate ph request base-disposition)
        disposition-fact (case disposition
                           :hold (cond-> (governor/hold-fact request context verdict)
                                   reason (assoc :phase-reason reason :phase ph))
                           :escalate {:t :approval-requested
                                      :op (:op request) :subject (:subject request)
                                      :reason (or reason
                                                  (cond (:high-stakes? verdict) :actuation
                                                        :else :low-confidence))}
                           :commit (commit-fact request context proposal))
        record (when (= :commit disposition)
                 (commit-record request context proposal))]
    {:disposition disposition
     :audit {:advisor-trace advisor-trace
             :governor-verdict verdict
             :phase-gate {:phase ph :base-disposition base-disposition :reason reason}
             :disposition-fact disposition-fact}
     :record record}))
