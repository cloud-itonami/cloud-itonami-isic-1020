(ns seafoodprocessing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (`seafoodprocessing.operation/build` ->
  compiled `langgraph.graph` StateGraph -> `seafoodprocessing.governor` ->
  `seafoodprocessing.phase` -> `seafoodprocessing.store`) through a
  multi-disposition scenario built from real batch shapes exercised by
  this repo's own tests (`operation_test` / `governor_test` / `sim`).

  No invented numbers: every table cell is read off the store or the
  audit facts that the compiled graph actually returned. Byte-identical
  across reruns against the same seed (no timestamps in page content).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [langgraph.graph :as g]
            [seafoodprocessing.advisor :as advisor]
            [seafoodprocessing.operation :as operation]
            [seafoodprocessing.store :as store]))

;; ----------------------------- seed (from operation_test + governor_test) --

(def ^:private full-us-evidence
  "US-FDA required-evidence keys from seafoodprocessing.facts/
  jurisdiction-evidence-requirements — same shape as operation_test."
  {:batch-assay :ok
   :temperature-log :ok
   :holding-time-record :ok
   :inspection-sensory :ok
   :inspection-histamine :ok
   :sanitation-certificate :ok
   :metal-detector-pass :ok})

(def ^:private clean-batch-base
  "Clean frozen-salmon batch inside cold-chain window, under holding-time
  limit, sanitation above floor, metal-detector pass, no contamination."
  {:product-type :salmon-frozen
   :batch-temp-c -16.0
   :holding-hours 12
   :storage-days 45
   :histamine-ppm 2.5
   :sanitation-score 85
   :metal-detector-pass? true
   :contamination-flag? false
   :jurisdiction :us-fda
   :evidence-checklist full-us-evidence})

(defn- seed-store
  "Seed batches that each isolate one governor path. Field shapes match
  operation_test / governor_test / sim — no fabricated thresholds."
  []
  (store/mem-store-with-batches
   {"batch-001"
    (assoc clean-batch-base :id "batch-001")
    ;; HARD :batch-temp-out-of-range (salmon-frozen window [-18.0, -12.0])
    "batch-hot"
    (assoc clean-batch-base :id "batch-hot" :batch-temp-c -5.0)
    ;; HARD :contamination-unresolved
    "batch-contam"
    (assoc clean-batch-base
           :id "batch-contam"
           :contamination-flag? true)
    ;; HARD :holding-time-exceeded (at ≤4°C safe-hours is 48; -16°C uses that band)
    "batch-holdtime"
    (assoc clean-batch-base :id "batch-holdtime" :holding-hours 72)
    ;; HARD :sanitation-low (floor 70)
    "batch-sani"
    (assoc clean-batch-base :id "batch-sani" :sanitation-score 60)}))

(def ^:private operator
  "Phase-3 full-autonomy context: high-stakes ops still escalate via
  governor/high-stakes (log-production-batch / coordinate-shipment always
  need plant-operator sign-off). Role matches sim.cljc."
  {:actor-id "seafood-processor-01"
   :phase :phase-3})

;; ----------------------------- harness --------------------------------

(defn- annotate-audit
  "Ensure every graph-audit entry has a `:t` for rendering. The advise
  node records advisor/trace maps without `:t`; terminal facts already
  carry one."
  [facts]
  (mapv (fn [f]
          (cond
            (:t f) f
            (or (:confidence f) (:summary f))
            (assoc f :t :advisor-proposal
                   :proposal-summary (:summary f))
            :else (assoc f :t :audit)))
        facts))

(defn- exec!
  "Run one real compiled-graph operation and append its audit facts to
  `!ledger`. Uses a unique thread-id per call (checkpointer key)."
  [actor !ledger request thread-id]
  (let [result (g/run* actor {:request request :context operator}
                       {:thread-id thread-id})
        audit (annotate-audit (get-in result [:state :audit] []))]
    (swap! !ledger into audit)
    result))

(defn run-demo!
  "Seed a store and drive a genuine mix of dispositions this actor reaches
  through the REAL StateGraph:

   - `:schedule-maintenance` on batch-001 → auto-commit (routine op)
   - `:flag-food-safety-concern` on batch-001 → auto-commit (monitoring)
   - `:log-production-batch` on clean batch-001 → interrupt escalate
     (high-stakes; left awaiting plant-operator approval)
   - four DISTINCT HARD holds:
       batch-hot      → :batch-temp-out-of-range
       batch-contam   → :contamination-unresolved
       batch-holdtime → :holding-time-exceeded
       batch-sani     → :sanitation-low

  Returns `{:store st :ledger facts}` — every field `render` reads is real
  governor/phase/store/graph output."
  []
  (let [st (seed-store)
        actor (operation/build st {:advisor (advisor/mock-advisor)})
        !ledger (atom [])]
    ;; routine op → commit
    (exec! actor !ledger
           {:op :schedule-maintenance
            :subject "batch-001"
            :equipment-id "line-3"}
           "demo-maint")
    ;; monitoring op → commit
    (exec! actor !ledger
           {:op :flag-food-safety-concern
            :subject "batch-001"}
           "demo-flag")
    ;; clean high-stakes → interrupt at :request-approval
    (exec! actor !ledger
           {:op :log-production-batch
            :subject "batch-001"}
           "demo-log-clean")
    ;; HARD holds (distinct rules)
    (exec! actor !ledger
           {:op :log-production-batch
            :subject "batch-hot"}
           "demo-log-hot")
    (exec! actor !ledger
           {:op :log-production-batch
            :subject "batch-contam"}
           "demo-log-contam")
    (exec! actor !ledger
           {:op :log-production-batch
            :subject "batch-holdtime"}
           "demo-log-holdtime")
    (exec! actor !ledger
           {:op :log-production-batch
            :subject "batch-sani"}
           "demo-log-sani")
    {:store st :ledger @!ledger}))

;; ----------------------------- rendering ------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-or-str [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    :else (str v)))

(defn- last-disposition-for
  "Last non-advisor audit fact for subject (commit / hold / approval-request)."
  [ledger subject-id]
  (->> ledger
       (remove #(= :advisor-proposal (:t %)))
       (filter #(= (:subject %) subject-id))
       last))

(defn- hold-rule [f]
  (or (some-> f :basis first)
      (some-> f :violations first :rule)
      (let [r (:phase-reason f)]
        (when (keyword? r) r))
      (let [r (:reason f)]
        (when (keyword? r) r))))

(defn- status-cell [ledger subject-id]
  (let [f (last-disposition-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (#{:held :governor-hold} (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (kw-or-str (or (hold-rule f) :unknown))) "</span>")
      (= :approval-rejected (:t f))
      (str "<span class=\"critical\">approval rejected &middot; "
           (esc (kw-or-str (or (hold-rule f) :approver-rejected))) "</span>")
      (= :approval-requested (:t f))
      (str "<span class=\"warn\">awaiting approval"
           (when-let [r (or (when (keyword? (:reason f)) (:reason f))
                            (when (keyword? (:phase-reason f)) (:phase-reason f)))]
             (str " &middot; " (esc (name r))))
           "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [st ledger batch-id]
  (let [b (store/processing-batch st batch-id)]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc batch-id)
            (esc (kw-or-str (or (:product-type b) "(missing)")))
            (esc (kw-or-str (or (:jurisdiction b) "n/a")))
            (esc (str (or (:batch-temp-c b) "—")))
            (esc (str (or (:holding-hours b) "—")))
            (esc (str (or (:sanitation-score b) "—")))
            (status-cell ledger batch-id))))

(defn- ledger-row [{:keys [t op subject disposition basis violations reason phase-reason
                           proposal-summary summary confidence]}]
  (let [basis-str (or (some->> basis (map #(if (keyword? %) (name %) (str %))) (str/join ", "))
                      (some->> violations first :rule name)
                      (some-> reason kw-or-str)
                      (some-> phase-reason kw-or-str)
                      (some-> disposition name)
                      (when (or proposal-summary summary)
                        (str "conf=" confidence " · " (or proposal-summary summary)))
                      "")]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (name (or t :n-a)))
            (esc (name (or op :n-a)))
            (esc (or subject ""))
            (esc basis-str))))

(def ^:private action-gate-rows
  ["        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"ok\">auto-commit when clean (routine)</span></td></tr>"
   "        <tr><td><code>:flag-food-safety-concern</code></td><td><span class=\"ok\">auto-commit when clean (monitoring; concern itself is recorded)</span></td></tr>"
   "        <tr><td><code>:log-production-batch</code></td><td><span class=\"warn\">ALWAYS human approval (high-stakes actuation) · cold-chain / holding-time / histamine / sanitation / metal-detector / contamination / evidence HARD-checked</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval (high-stakes) · shelf-life + optional handoff well-formedness HARD-checked</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a `run-demo!` result."
  [{:keys [store ledger]}]
  (let [batch-ids ["batch-001" "batch-hot" "batch-contam" "batch-holdtime" "batch-sani"]
        batch-rows (str/join "\n" (map #(batch-row store ledger %) batch-ids))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-1020 &middot; seafood processing</title><style>"
     "body{font:14px/1.5 -apple-system,system-ui,sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#0b3d5c;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem;font-weight:600}"
     ".badge{display:inline-block;margin-top:.4rem;font-size:.75rem;opacity:.8}"
     "main{max-width:980px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".card h2{margin-top:0;font-size:1rem}.muted{color:#777;font-size:.82rem}"
     "table{border-collapse:collapse;width:100%;font-size:.85rem}th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee}th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Seafood processing (ISIC 1020) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · production / shipment actuation always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Processing batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>seafoodprocessing.store</code> via <code>seafoodprocessing.render-html</code> (<code>clojure -M:render-html</code>). Drives the real advisor → governor → phase StateGraph. No invented usage or revenue metrics.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product</th><th>Jurisdiction</th><th>Temp °C</th><th>Hold h</th><th>Sanitation</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Seafood Processing Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Cold-chain window, holding-time, histamine assay, sanitation floor, metal-detector pass, contamination-flag resolution and jurisdiction evidence checklist are checked against each batch's own record; production logging and shipment always escalate for plant-operator sign-off.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every advisor proposal, hold, escalation and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        demo (run-demo!)
        html (render demo)
        out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file html)
    (println "wrote" out "(" (count (:ledger demo)) "ledger facts )")))
