(ns seafoodprocessing.store
  "In-memory (or swappable) store for batch metadata during an operation.
  Seam point for Datomic/kotoba-server integration.

  Production deployment: inject a Datomic-backed implementation.
  Testing: inject MemStore below or a mock.

  `MemStore`'s batch-metadata half (`processing-batch`/`add-batch`/
  `update-batch`/`resolve-contamination-flag`) is unchanged from before
  this namespace grew a ledger: each still returns a NEW `MemStore`
  wrapping an updated `batches` map, exactly as `store_test.cljc`
  already exercises it.

  The append-only audit ledger (`ledger`/`append-ledger!`) is this
  actor's core missing plumbing until now:
  `seafoodprocessing.operation`'s `:commit`/`:hold` graph nodes append
  every committed/held/approval-rejected decision fact here, so a
  batch's food-safety decision history is always a query over an
  immutable log -- the same discipline every sibling
  `cloud-itonami-isic-*` actor's ledger provides (e.g. `transportops.store`,
  `cerealops.store`). It backs onto a plain atom shared across every
  `(MemStore. new-batches ledger-atom)` returned by the batch-metadata
  methods above, so appending to the ledger is a genuine side effect
  visible to the caller that built the store, regardless of which
  `MemStore` instance (post batch-update) is holding the reference.")

(defprotocol BatchStore
  "Protocol for batch metadata queries."
  (processing-batch [store batch-id]
    "Return batch metadata by ID, or nil if not found.")
  (add-batch [store batch-id batch-metadata]
    "Return new store with batch added.")
  (update-batch [store batch-id f]
    "Return new store with batch updated by applying f to current record.")
  (resolve-contamination-flag [store batch-id]
    "Mark contamination flag as resolved, return new store.")
  (ledger [store]
    "The append-only audit ledger: every committed/held/
    approval-rejected decision fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact."))

(deftype MemStore [batches ledger-atom]
  BatchStore
  (processing-batch [_ batch-id]
    (get batches batch-id))
  (add-batch [_ batch-id batch-metadata]
    (MemStore. (assoc batches batch-id batch-metadata) ledger-atom))
  (update-batch [_ batch-id f]
    (MemStore. (update batches batch-id f) ledger-atom))
  (resolve-contamination-flag [_ batch-id]
    (update-batch (MemStore. batches ledger-atom) batch-id
                  #(assoc % :contamination-flag? false)))
  (ledger [_] @ledger-atom)
  (append-ledger! [_ fact]
    (swap! ledger-atom conj fact)
    fact))

(defn mem-store
  "Create an empty in-memory batch store."
  []
  (MemStore. {} (atom [])))

(defn mem-store-with-batches
  "Create a store pre-populated with test batches."
  [batch-map]
  (MemStore. batch-map (atom [])))
