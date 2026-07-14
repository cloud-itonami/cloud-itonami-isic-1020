(ns seafoodprocessing.phase
  "Rollout phase gates for the Seafood Processing actor.")

(def default-phase :phase-1)

(def phases
  {:phase-0 {:name "Testing" :locked? true :allow #{} :escalate-all? true}
   :phase-1 {:name "Staging" :locked? false :allow #{:flag-food-safety-concern} :escalate-all? true}
   :phase-2 {:name "Early Access" :locked? false :allow #{:flag-food-safety-concern :log-production-batch} :escalate-all? false}
   :phase-3 {:name "Production" :locked? false :allow #{:flag-food-safety-concern :log-production-batch :coordinate-shipment :schedule-maintenance} :escalate-all? false}})

(defn verdict->disposition
  "Convert Governor verdict to initial disposition before phase gate."
  [verdict]
  (cond
    (seq (:violations verdict)) :hold
    (:high-stakes? verdict) :escalate
    :else :commit))

(defn gate
  "Apply phase-specific constraints to the disposition."
  [phase request base-disposition]
  (let [{:keys [op]} request
        phase-def (get phases phase default-phase)
        allowed (:allow phase-def)
        locked? (:locked? phase-def)
        escalate-all? (:escalate-all? phase-def)]
    (cond
      locked?
      {:disposition :hold :reason "Phase is locked (testing only)"}

      (not (contains? allowed op))
      {:disposition :hold :reason (str "Operation " op " not allowed in phase " phase)}

      escalate-all?
      {:disposition :escalate :reason "Phase requires escalation for all operations"}

      :else
      {:disposition base-disposition})))
