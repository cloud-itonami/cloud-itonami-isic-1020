(ns seafoodprocessing.operation
  "OperationActor -- one seafood-processing operation = one supervised
  actor run, expressed as a REAL compiled `langgraph-clj` `StateGraph`
  (`langgraph.graph/state-graph` + `compile-graph`). The advisor
  (SeafoodProcessingAdvisor) is sealed into a single node (`:advise`);
  its proposal is ALWAYS routed through the independent
  `SeafoodProcessingGovernor` (`:govern`) and the rollout phase gate
  (`:decide`) before anything commits to the SSoT.

  This replaces the previous `run-operation`, which was a plain
  `let`-threaded advisor -> governor -> phase pipeline that never
  required `langgraph.graph` and never touched `state-graph`/
  `add-node`/`compile-graph` at all.

  State machine:
  intake -> advise -> govern -> decide -+-> commit
                                         +-> request-approval -> commit
                                         +-> hold

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`seafoodprocessing.store/MemStore`, or any
                     `BatchStore` impl)
    - the Advisor  (mock today; `seafoodprocessing.advisor/Advisor` is
                     already the injection point -- see its docstring)
    - the Phase    (0->3 rollout; passed per-request via `:phase` in
                     `:context`, not frozen at `build` time -- matches
                     the old `run-operation`'s call-time `context`
                     argument)

  One graph run = one seafood-processing operation. No unbounded inner
  loop -- each run is auditable and checkpointed. Every commit/hold/
  approval-rejected decision fact lands in `seafoodprocessing.store`'s
  append-only ledger (`store/append-ledger!`) -- newly added to
  `MemStore` alongside its existing `BatchStore` batch-metadata
  protocol (unchanged; every existing `store_test.cljc` assertion
  still passes unmodified) so this actor gets the SAME immutable
  decision-history discipline every sibling `cloud-itonami-isic-*`
  actor's ledger already provides.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor at the
  `:request-approval` node until a human plant operator resumes it
  with a decision. `:log-production-batch` and `:coordinate-shipment`
  ALWAYS reach this node when the Governor is clean -- see
  `seafoodprocessing.governor/high-stakes` (this graph does not change
  that pre-existing policy, only wires it into real nodes)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [seafoodprocessing.advisor :as advisor]
            [seafoodprocessing.governor :as governor]
            [seafoodprocessing.phase :as phase]
            [seafoodprocessing.store :as store]))

;; ============================================================================
;; Audit-fact builders
;; ============================================================================

(defn- commit-fact
  "The audit fact written when a proposal commits. seafoodprocessing has
  no separate stateful commit-record! entity beyond the batch
  directory, so the ledger fact itself is the durable record of what
  happened."
  [request context proposal]
  {:t           :committed
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :commit
   :basis       (:cites proposal)
   :summary     (:summary proposal)})

(defn- commit-record
  "The SSoT-application effect descriptor for a committed proposal --
  identical shape to the pre-graph `run-operation`'s `:record`."
  [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

;; ============================================================================
;; Compiled StateGraph
;; ============================================================================

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor      -- a `seafoodprocessing.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)

  The compiled graph's input map: `{:request .. :context ..}` (context
  carries `:actor-id`/`:phase`, per-request exactly as the old
  `run-operation`'s `context` argument was, not frozen at `build`
  time)."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              ;; HARD governor violations OR a phase gate that closes
              ;; the door (locked / op not allowed) both land here --
              ;; NEVER routed through human approval, straight to :hold.
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t          :approval-requested
                        :op         (:op request)
                        :subject    (:subject request)
                        :reason     (or reason
                                        (cond (:high-stakes? verdict) :actuation
                                              :else :low-confidence))
                        :phase      ph
                        :confidence (:confidence proposal)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:held :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
