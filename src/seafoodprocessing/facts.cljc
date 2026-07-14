(ns seafoodprocessing.facts
  "Domain facts for seafood processing (ISIC 1020): fish, crustaceans, molluscs.

  Core hazards in seafood processing:
    - Histamine formation (scombroid fish in warm conditions) -> neurotoxin
    - Vibrio spp. (raw, undercooked, cross-contamination)
    - Botulism (anaerobic packaging, temperature abuse)
    - Parasites (roundworms, tapeworms in raw/undercooked)
    - Allergens (shellfish proteins, iodine in seaweed)
    - Cold-chain integrity (critical for all frozen/chilled products)
    - Shelf-life limits (species + storage temp dependent)

  This actor coordinates production metadata (batch intake, inspection records,
  traceability) and proposes scheduling for cold-chain maintenance, quality
  testing, and shipment. It does NOT control processing equipment or make
  food-safety determinations; those remain exclusive to licensed plant operators.

  Hard gates (Governor only):
    - Batch temperature within cold-chain window [min, max]
    - Holding time at temperature not exceeded (pathogen/histamine risk)
    - Species-specific shelf-life window respected
    - Required inspection records (sensory, histamine, Vibrio, allergen) present
    - Sanitation score sufficient
    - Metal detector passed all product
    - No unresolved contamination flag")

;; Product types in 1020: low-hazard to high-hazard categories
(def product-catalog
  [{:id :salmon-frozen
    :display "Frozen Atlantic Salmon"
    :category :finfish
    :cold-chain-temp-min-c -18.0
    :cold-chain-temp-max-c -12.0
    :shelf-life-days-at-min-temp 365
    :histamine-risk :moderate
    :required-inspections #{:sensory :histamine}}

   {:id :canned-tuna
    :display "Canned Yellowfin Tuna"
    :category :finfish
    :cold-chain-temp-min-c 0.0
    :cold-chain-temp-max-c 25.0
    :shelf-life-days-at-min-temp 365
    :histamine-risk :high
    :required-inspections #{:sensory :histamine}}

   {:id :live-shrimp
    :display "Live White Shrimp"
    :category :crustacean
    :cold-chain-temp-min-c 2.0
    :cold-chain-temp-max-c 4.0
    :shelf-life-days-at-min-temp 7
    :histamine-risk :moderate
    :required-inspections #{:sensory :vibrio}}

   {:id :oyster-raw
    :display "Raw Oysters (Live)"
    :category :mollusc
    :cold-chain-temp-min-c 2.0
    :cold-chain-temp-max-c 4.0
    :shelf-life-days-at-min-temp 14
    :histamine-risk :low
    :required-inspections #{:sensory :vibrio :allergen}}

   {:id :squid-frozen
    :display "Frozen Squid Rings"
    :category :mollusc
    :cold-chain-temp-min-c -18.0
    :cold-chain-temp-max-c -12.0
    :shelf-life-days-at-min-temp 180
    :histamine-risk :low
    :required-inspections #{:sensory}}])

(defn product-type-by-id
  "Look up product definition by ID."
  [id]
  (some #(when (= id (:id %)) %) product-catalog))

;; Jurisdiction codes: must be explicit in every proposal
(def jurisdictions
  #{:us-fda :eu-fsanz :jp-mhlw :sg-avs :au-daff})

(defn valid-jurisdiction? [j]
  (contains? jurisdictions j))

;; Evidence checklist: what inspections/docs are required by jurisdiction
(def jurisdiction-evidence-requirements
  {:us-fda
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :sanitation-certificate :required
    :metal-detector-pass :required}

   :eu-fsanz
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :inspection-vibrio :required
    :sanitation-certificate :required
    :metal-detector-pass :required
    :allergen-declaration :required}

   :jp-mhlw
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :sanitation-certificate :required
    :metal-detector-pass :required}

   :sg-avs
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :sanitation-certificate :required
    :metal-detector-pass :required}

   :au-daff
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :sanitation-certificate :required
    :metal-detector-pass :required
    :allergen-declaration :required}})

(defn required-evidence
  "Return the set of required evidence keys for a jurisdiction."
  [jurisdiction]
  (-> (get jurisdiction-evidence-requirements jurisdiction)
      keys
      set))

(defn required-evidence-satisfied?
  "Check if all required evidence for a jurisdiction is present in the checklist."
  [jurisdiction checklist]
  (let [required (required-evidence jurisdiction)
        satisfied (set (keys checklist))]
    (clojure.set/subset? required satisfied)))

;; Histamine thresholds (mg/100g or ppm) by jurisdiction
(def histamine-limits
  {:us-fda 5.0
   :eu-fsanz 10.0
   :jp-mhlw 5.0
   :sg-avs 10.0
   :au-daff 10.0})

(defn histamine-pass?
  "Check if batch histamine assay passes for a jurisdiction."
  [jurisdiction batch-histamine-ppm]
  (let [limit (get histamine-limits jurisdiction)]
    (<= batch-histamine-ppm limit)))

;; Sanitation scoring: 0-100, where <70 is failing
(def sanitation-floor 70)

(defn sanitation-acceptable?
  "Check if sanitation score meets floor."
  [score]
  (>= score sanitation-floor))

;; Shelf-life tracking: storage duration at cold-chain temp
(defn within-shelf-life?
  "Check if batch stored duration is within shelf-life limit for product."
  [product-id storage-days-at-temp]
  (let [product (product-type-by-id product-id)
        limit (:shelf-life-days-at-min-temp product)]
    (when product
      (<= storage-days-at-temp limit))))

;; Holding time (time since capture/processing start): pathogen/histamine risk
;; Scombroid fish (tuna, mackerel, bonito): histamine forms rapidly if temp >10°C
;; General guideline: 48 hours @ 0°C is safe for most species; above 4°C, 12-24 hours
(def holding-time-limits
  "Max hours at given temp before pathogen/histamine multiplication risk."
  {;; Chilled (0-4°C)
   :temp-0-4 {:safe-hours 48 :risk-level :low}
   ;; Ambient (10-15°C)
   :temp-10-15 {:safe-hours 12 :risk-level :high}
   ;; Above 15°C
   :temp-above-15 {:safe-hours 4 :risk-level :critical}})

(defn holding-time-acceptable?
  "Check if holding time at storage temperature is within safe limits."
  [storage-temp-c holding-hours]
  (let [limit (cond
                (<= storage-temp-c 4.0) (:safe-hours (:temp-0-4 holding-time-limits))
                (<= storage-temp-c 15.0) (:safe-hours (:temp-10-15 holding-time-limits))
                :else (:safe-hours (:temp-above-15 holding-time-limits)))]
    (<= holding-hours limit)))
