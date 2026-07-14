(ns seafoodprocessing.governor
  "Seafood Processing Governor -- the independent compliance layer that earns
  the SeafoodProcessingAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's actual temperature lies inside its cold-chain window
    - Whether holding time has been exceeded (histamine/pathogen risk)
    - Whether plant sanitation meets jurisdiction requirements
    - Whether metal detector passed all product
    - Whether an open contamination flag has been resolved
    - Whether histamine assay is below jurisdiction limits
    - Whether a batch's evidence checklist is complete per jurisdiction

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct equipment/line control (NEVER done by this actor -- processing
  equipment operation remains exclusive to licensed plant operators), the
  Governor operates on batch metadata: provenance, timing, cold-chain integrity,
  histamine testing, sanitation records, and food-safety flags. This is
  plant-operations coordination, not process control.

  CRITICAL: Any proposal involving food-safety concerns (contamination,
  sanitation, temperature breach, histamine risk, pathogen risk) ALWAYS
  escalates to human operator for final sign-off. The LLM's confidence is
  never sufficient for food-safety decisions."
  (:require [seafoodprocessing.facts :as facts]
            [seafoodprocessing.registry :as registry]
            [seafoodprocessing.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are the
  two real-world actuation events this actor performs. Both require plant
  operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "提案は法域のfood-safety specificationの引用を含む必要があります"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)
          j (:jurisdiction b)]
      (when-not (and b j
                     (facts/required-evidence-satisfied?
                      j
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類が充足していない"}]))))

(defn- batch-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's actual
  temperature stays inside its cold-chain window [min,max]."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:batch-temp-c b)
                 (registry/batch-temp-out-of-range?
                  (:batch-temp-c b)
                  (:cold-chain-temp-min-c p)
                  (:cold-chain-temp-max-c p)))
        [{:rule :batch-temp-out-of-range
          :detail (str subject " の温度が冷蔵窓外")}]))))

(defn- holding-time-exceeded-violations
  "For `:log-production-batch`, check if holding time exceeds safe window."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:batch-temp-c b) (:holding-hours b))
        (when (registry/holding-time-exceeded? (:batch-temp-c b) (:holding-hours b))
          [{:rule :holding-time-exceeded
            :detail (str subject " の保持時間が限界を超過")}])))))

(defn- histamine-violation-violations
  "For `:log-production-batch`, check if histamine assay exceeds jurisdiction limit."
  [{:keys [op subject]} st jurisdiction]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:histamine-ppm b) jurisdiction)
        (when (registry/histamine-exceeds-limit? jurisdiction (:histamine-ppm b))
          [{:rule :histamine-high
            :detail (str subject " のヒスタミンが限界超過")}])))))

(defn- sanitation-score-violations
  "For `:log-production-batch`, check if sanitation score meets floor."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (:sanitation-score b))
        (when (registry/sanitation-below-floor? (:sanitation-score b))
          [{:rule :sanitation-low
            :detail (str subject " の衛生スコアが最低基準未満")}])))))

(defn- metal-detector-violations
  "For `:log-production-batch`, check if metal detector passed all product."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/processing-batch st subject)]
      (when (and b (not (:metal-detector-pass? b)))
        [{:rule :metal-detector-fail
          :detail (str subject " が金属検出器検査に合格していない")}]))))

(defn- shelf-life-violations
  "For `:coordinate-shipment`, check if batch shelf-life is not expired."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (let [b (store/processing-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:storage-days b))
        (when (registry/shelf-life-expired? (:product-type b) (:storage-days b))
          [{:rule :shelf-life-expired
            :detail (str subject " の保存期限が終了")}])))))

(defn- contamination-flag-unresolved
  "Check if there's an open food-safety concern flag on the batch."
  [{:keys [op subject]} st]
  (when (contains? #{:log-production-batch :coordinate-shipment} op)
    (let [b (store/processing-batch st subject)]
      (when (and b (:contamination-flag? b))
        [{:rule :contamination-unresolved
          :detail (str subject " に未解決の汚染フラグあり")}]))))

(defn check
  "Run all Governor checks on a proposal. Returns {:violations [...], :high-stakes? bool}."
  [request context proposal st]
  (let [jurisdiction (-> proposal :value :jurisdiction)
        all-violations (concat
                        (spec-basis-violations request proposal)
                        (evidence-incomplete-violations request st)
                        (batch-temp-out-of-range-violations request st)
                        (holding-time-exceeded-violations request st)
                        (histamine-violation-violations request st jurisdiction)
                        (sanitation-score-violations request st)
                        (metal-detector-violations request st)
                        (shelf-life-violations request st)
                        (contamination-flag-unresolved request st))
        high-stakes? (contains? high-stakes (:op request))]
    {:violations all-violations
     :high-stakes? high-stakes?}))

(defn hold-fact
  "Audit fact recorded when Governor rejects a proposal."
  [request context verdict]
  {:t :held
   :op (:op request)
   :subject (:subject request)
   :actor (:actor-id context)
   :disposition :hold
   :violations (:violations verdict)
   :reason "Governor rejection or escalation required"})
