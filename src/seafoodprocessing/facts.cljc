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
    - No unresolved contamination flag

  CITATION PROVENANCE (2026-07-25). `jurisdiction-authorities` below carries
  the legal basis for each jurisdiction, read out of a directly-fetched
  official primary source and re-grepped against the raw markup:

    - EU: Reg. (EC) No 2073/2005 on microbiological criteria (EUR-Lex
      CELEX:32005R2073), Annex I category 1.26 -- histamine in fishery
      products from species associated with a high amount of histidine:
      n=9, c=2, `\"100 mg/kg\"` (m) and `\"200 mg/kg\"` (M) by HPLC, applied to
      `\"Products placed on the market during their shelf-life\"`. Category
      1.27 (enzyme-maturation/brine products, i.e. fish sauce) is looser:
      `\"200 mg/kg\"` / `\"400 mg/kg\"`. Also Reg. (EC) No 853/2004 Annex III
      (CELEX:32004R0853): `\"frozen fishery products, with the exception of
      frozen fish in brine intended for the manufacture of canned food,
      must be maintained during transport at an even temperature of\"` `\"not
      more than -18oC\"`.
    - US: 21 CFR Part 123 (Fish and Fishery Products HACCP) via govinfo.gov
      official CFR XML -- 123.6 `\"Hazard analysis and Hazard Analysis
      Critical Control Point (HACCP) plan.\"` DISCLOSED SCOPE LIMIT: Part 123
      mandates the HACCP system but does NOT state a numeric histamine
      limit. FDA's 50 ppm decomposition / 500 ppm toxicity figures live in
      the Fish and Fishery Products Hazards and Controls Guidance, which is
      guidance rather than regulation, so `:legal-basis` for the US cites
      the CFR for the HACCP duty and names the guidance separately instead
      of pretending the number is codified.

  CORRECTION -- MISATTRIBUTED EU AUTHORITY. The jurisdiction keyword
  `:eu-fsanz` is a misnomer: FSANZ is **Food Standards Australia New
  Zealand**, not an EU body (and this catalog already has a separate
  `:au-daff` entry for Australia). The EU's actual instruments are
  Reg. (EC) 853/2004 and Reg. (EC) 2073/2005. `:eu` is now the correct key;
  `:eu-fsanz` is KEPT as a deprecated alias so no existing caller breaks,
  and `deprecated-jurisdiction-aliases` records the mapping rather than
  leaving the wrong name to look authoritative.

  UNIT HAZARD -- histamine. `histamine-limits` was documented as
  `\"(mg/100g or ppm)\"`, but those differ by a factor of 10, and
  `histamine-pass?` named its parameter `batch-histamine-ppm` while
  comparing against values that are actually mg/100g (EU 10.0 = the verified
  100 mg/kg = 100 ppm; US 5.0 = FDA's 50 ppm). The numbers were right; the
  unit label was wrong. Fixed by naming the unit explicitly in the parameter
  and adding `histamine-limits-mg-per-kg` for the ppm-equivalent figures.
  NO numeric threshold changed, so no gate moved -- see
  `histamine-pass-ppm?` for an unambiguous ppm entry point.

  NOTED, NOT SILENTLY CHANGED -- frozen window vs EU law. `:salmon-frozen`
  and `:squid-frozen` allow a cold-chain max of -12.0 degC, which is LOOSER
  than the -18 degC that 853/2004 Annex III requires for frozen fishery
  products in transit. The statutory figure is recorded in
  `jurisdiction-authorities` under `:statutory-limits`, and
  `frozen-window-meets-eu-transit-limit?` makes the gap machine-checkable,
  but the product windows are left as they are: tightening the max to -18.0
  would collapse `[-18.0, -12.0]` into a single point and reject every real
  measurement with any variance. Narrowing it correctly needs a product-and-
  jurisdiction-aware window, which is a design change, not a fact fix."
  ;; clojure.set is required explicitly: `required-evidence-satisfied?` calls
  ;; `set/subset?`, which only resolved because the JVM already had
  ;; clojure.set loaded and would break a ClojureScript build of this .cljc.
  ;; Pre-existing latent portability bug, fixed 2026-07-25.
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]))

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
  #{:us-fda :eu :eu-fsanz :jp-mhlw :sg-avs :au-daff})

(def deprecated-jurisdiction-aliases
  "Wrong-but-still-accepted jurisdiction keys, mapped to the correct one.
  `:eu-fsanz` named FSANZ (Food Standards Australia New Zealand) as if it
  were the EU authority; the EU's instruments are Reg. (EC) 853/2004 and
  Reg. (EC) 2073/2005. Kept so existing callers keep working."
  {:eu-fsanz :eu})

(defn canonical-jurisdiction
  "Resolve a jurisdiction key through `deprecated-jurisdiction-aliases`."
  [j]
  (get deprecated-jurisdiction-aliases j j))

(defn valid-jurisdiction? [j]
  (contains? jurisdictions j))

(def jurisdiction-authorities
  "The real regulator and the fetched primary source behind each
  jurisdiction's limits. See this namespace's docstring for the provenance
  and for what is deliberately NOT claimed."
  {:eu
   {:id :eu
    :authority "European Commission / EFSA"
    :legal-basis "Regulation (EC) No 2073/2005 Annex I, category 1.26 (histamine in fishery products from species associated with a high amount of histidine: n=9, c=2, m=100 mg/kg, M=200 mg/kg, HPLC) and category 1.27 (enzyme-maturation/brine products: m=200 mg/kg, M=400 mg/kg); Regulation (EC) No 853/2004 Annex III (frozen fishery products maintained during transport at not more than -18 °C)"
    :provenance "https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32005R2073"
    :provenance-hygiene "https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32004R0853"
    :verbatim
    {:histamine-high-histidine "Histamine 9 (17) 2 100 mg/kg 200 mg/kg HPLC (18) Products placed on the market during their shelf-life"
     :histamine-brine-matured "Histamine 9 2 200 mg/kg 400 mg/kg HPLC (18) Products placed on the market during their shelf-life"
     :frozen-transport "frozen fishery products, with the exception of frozen fish in brine intended for the manufacture of canned food, must be maintained during transport at an even temperature of ... not more than -18oC"}
    :statutory-limits
    {:histamine-m-mg-per-kg 100.0
     :histamine-M-mg-per-kg 200.0
     :histamine-brine-m-mg-per-kg 200.0
     :histamine-brine-M-mg-per-kg 400.0
     :frozen-transport-max-temp-c -18.0}}

   :us-fda
   {:id :us-fda
    :authority "U.S. Food and Drug Administration"
    :legal-basis "21 CFR Part 123 (Fish and Fishery Products), §123.6 \"Hazard analysis and Hazard Analysis Critical Control Point (HACCP) plan.\" — mandates the HACCP system. The numeric 50 ppm decomposition / 500 ppm toxicity histamine figures are NOT in Part 123; they come from FDA's Fish and Fishery Products Hazards and Controls Guidance, which is guidance, not regulation."
    :provenance "https://www.govinfo.gov/content/pkg/CFR-2024-title21-vol2/xml/CFR-2024-title21-vol2-sec123-6.xml"
    :verbatim
    {:haccp-plan-section "Hazard analysis and Hazard Analysis Critical Control Point (HACCP) plan."}
    :statutory-limits
    {:histamine-numeric-limit-in-cfr? false
     :histamine-guidance-decomposition-ppm 50.0
     :histamine-guidance-toxicity-ppm 500.0
     :histamine-guidance-is-regulation? false}}})

(defn authority-for
  "The verified authority/citation record for a jurisdiction, resolving
  deprecated aliases. nil when this catalog has no citation for it -- an
  uncited jurisdiction is a coverage gap, never silently treated as covered."
  [jurisdiction]
  (get jurisdiction-authorities (canonical-jurisdiction jurisdiction)))

(defn jurisdiction-cited?
  "True only when the jurisdiction has a legal-basis, an http(s) provenance
  URL and at least one verbatim quote."
  [jurisdiction]
  (let [{:keys [legal-basis provenance verbatim]} (authority-for jurisdiction)]
    (boolean (and (string? legal-basis) (seq legal-basis)
                  (string? provenance) (str/starts-with? provenance "http")
                  (map? verbatim) (seq verbatim)))))

(defn citation-coverage
  "Honest report: which jurisdictions in `jurisdictions` rest on a fetched
  official source and which are still uncited."
  []
  (let [ids (sort-by str jurisdictions)
        cited (filter jurisdiction-cited? ids)]
    {:jurisdictions (count jurisdictions)
     :cited (count cited)
     :cited-jurisdictions (vec cited)
     :uncited-jurisdictions (vec (remove jurisdiction-cited? ids))
     :deprecated-aliases deprecated-jurisdiction-aliases
     :note (str "cloud-itonami-isic-1020: " (count cited) "/" (count jurisdictions)
                " jurisdiction keys resolve to a directly-fetched official "
                "source (EUR-Lex 2073/2005 + 853/2004, govinfo 21 CFR 123). "
                ":jp-mhlw, :sg-avs and :au-daff carry limits with NO fetched "
                "citation yet -- that is a coverage gap, not an exemption. "
                ":eu-fsanz was a misattributed authority name (FSANZ is "
                "Australia/New Zealand) and is now a deprecated alias for :eu.")}))

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

   ;; :eu is the correct key; :eu-fsanz below is the deprecated alias kept
   ;; byte-identical so both resolve to the same requirements. Adding :eu
   ;; WITHOUT this entry would have made `required-evidence :eu` empty and
   ;; the Governor's evidence gate vacuous for it.
   :eu
   {:batch-assay :histamine-ppm
    :temperature-log :required
    :holding-time-record :required
    :inspection-sensory :required
    :inspection-histamine :required
    :inspection-vibrio :required
    :sanitation-certificate :required
    :metal-detector-pass :required
    :allergen-declaration :required}

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
  "Return the set of required evidence keys for a jurisdiction, resolving
  deprecated aliases. Returns nil -- NOT an empty set -- for a jurisdiction
  this catalog does not know, so callers can tell 'nothing required' apart
  from 'no such jurisdiction'."
  [jurisdiction]
  (some-> (get jurisdiction-evidence-requirements (canonical-jurisdiction jurisdiction))
          keys
          set))

(defn required-evidence-satisfied?
  "Check if all required evidence for a jurisdiction is present in the
  checklist.

  Returns FALSE for a jurisdiction this catalog does not know. Before
  2026-07-25 an unknown jurisdiction produced an empty required-set, and
  since the empty set is a subset of everything, the check passed
  VACUOUSLY -- a batch tagged with a jurisdiction that is not in
  `jurisdiction-evidence-requirements` cleared the Governor's
  `:evidence-incomplete` hard gate with no evidence at all
  (`seafoodprocessing.governor` only checked that the jurisdiction was
  non-nil, not that it was known). Failing closed is the safe direction."
  [jurisdiction checklist]
  (if-let [required (required-evidence jurisdiction)]
    (set/subset? required (set (keys checklist)))
    false))

;; Histamine thresholds by jurisdiction, in mg/100g.
;;
;; UNIT: mg per 100 g, NOT ppm. These differ by a factor of 10 and the old
;; comment said "(mg/100g or ppm)" as if they were interchangeable. The
;; values are correct as mg/100g and are cross-checked against the fetched
;; sources: EU 10.0 mg/100g == the verified 100 mg/kg (Reg. 2073/2005 Annex I
;; cat. 1.26 m value); US 5.0 mg/100g == FDA's 50 ppm guidance figure.
;; No threshold was changed in this pass -- only the unit was made honest.
(def histamine-limits
  {:us-fda 5.0
   :eu 10.0
   :eu-fsanz 10.0   ; deprecated alias, kept in sync with :eu
   :jp-mhlw 5.0
   :sg-avs 10.0
   :au-daff 10.0})

(def histamine-limits-mg-per-kg
  "The same limits expressed as mg/kg (numerically equal to ppm), so callers
  working in ppm never have to guess. 1 mg/100g = 10 mg/kg = 10 ppm."
  (into {} (for [[j v] histamine-limits] [j (* 10.0 v)])))

(defn histamine-pass?
  "Check if batch histamine assay passes for a jurisdiction.

  `batch-histamine-mg-per-100g` is in mg/100g, matching `histamine-limits`.
  If your assay reports ppm (mg/kg), use `histamine-pass-ppm?` instead --
  passing a ppm figure here would compare a number 10x too large against
  the limit and fail every batch."
  [jurisdiction batch-histamine-mg-per-100g]
  (let [limit (get histamine-limits (canonical-jurisdiction jurisdiction))]
    (and (some? limit) (<= batch-histamine-mg-per-100g limit))))

(defn histamine-pass-ppm?
  "Unambiguous ppm (mg/kg) entry point for the same gate."
  [jurisdiction batch-histamine-ppm]
  (let [limit (get histamine-limits-mg-per-kg (canonical-jurisdiction jurisdiction))]
    (and (some? limit) (<= batch-histamine-ppm limit))))

(defn frozen-window-meets-eu-transit-limit?
  "Does `product-id`'s cold-chain window stay at or below the -18 °C that
  Reg. (EC) 853/2004 Annex III requires for frozen fishery products in
  transit? Makes the known gap machine-checkable instead of prose-only:
  this returns false for the frozen products in `product-catalog` today,
  whose window tops out at -12 °C. Non-frozen products return nil (the
  transit rule does not apply to them)."
  [product-id]
  (let [{:keys [cold-chain-temp-max-c]} (product-type-by-id product-id)
        eu-limit (-> jurisdiction-authorities :eu :statutory-limits
                     :frozen-transport-max-temp-c)]
    (when (and cold-chain-temp-max-c (neg? cold-chain-temp-max-c))
      (<= cold-chain-temp-max-c eu-limit))))

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

;; ─────────── Downstream Cross-Actor Handoff (optional, isic-1020 -> isic-1075) ───────────
;;
;; `:coordinate-shipment` proposals MAY OPTIONALLY carry a `:handoff`
;; record under the proposal's `:value` when this actor dispatches a
;; finished seafood batch to a downstream cook-chill/cook-freeze
;; prepared-meal manufacturer (e.g. cloud-itonami-isic-1075). Reuses the
;; SAME `:handoff/*` wire shape isic-1075 already uses for its own
;; downstream isic-1075<->jsic-4721 handoff -- see superproject
;; ADR-2800000800. A `:handoff` here is OPTIONAL, not required: existing
;; shipment proposals worked before this field existed and keep working
;; unchanged with no `:handoff` attached at all.
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-isic-1020"
;;    :handoff/batch-id "..."
;;    :handoff/product-type-id :salmon-frozen
;;    :handoff/quantity-kg 500.0
;;    :handoff/dispatched-at-iso "..."}

(defn handoff-record-well-formed?
  "Positive-sense convenience predicate: does `handoff` carry every
  REQUIRED `:handoff/*` field (id/source-actor/batch-id/product-type-id/
  quantity-kg/dispatched-at-iso) with a plausible value (quantity-kg a
  positive number, the string fields non-blank)? Never validates the
  OPTIONAL cold-chain/unspsc/gtin fields."
  [handoff]
  (boolean
   (and (map? handoff)
        (seq (:handoff/id handoff))
        (seq (:handoff/source-actor handoff))
        (seq (:handoff/batch-id handoff))
        (some? (:handoff/product-type-id handoff))
        (number? (:handoff/quantity-kg handoff))
        (pos? (:handoff/quantity-kg handoff))
        (seq (:handoff/dispatched-at-iso handoff)))))
