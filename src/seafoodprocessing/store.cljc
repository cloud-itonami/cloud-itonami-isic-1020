(ns seafoodprocessing.store
  "In-memory (or swappable) store for batch metadata during an operation.
  Seam point for Datomic/kotoba-server integration.

  Production deployment: inject a Datomic-backed implementation.
  Testing: inject MemStore below or a mock.")

(defprotocol BatchStore
  "Protocol for batch metadata queries."
  (processing-batch [store batch-id]
    "Return batch metadata by ID, or nil if not found.")
  (add-batch [store batch-id batch-metadata]
    "Return new store with batch added.")
  (update-batch [store batch-id f]
    "Return new store with batch updated by applying f to current record.")
  (resolve-contamination-flag [store batch-id]
    "Mark contamination flag as resolved, return new store."))

(deftype MemStore [batches]
  BatchStore
  (processing-batch [_ batch-id]
    (get batches batch-id))
  (add-batch [_ batch-id batch-metadata]
    (MemStore. (assoc batches batch-id batch-metadata)))
  (update-batch [_ batch-id f]
    (MemStore. (update batches batch-id f)))
  (resolve-contamination-flag [_ batch-id]
    (update-batch (MemStore. batches) batch-id
                  #(assoc % :contamination-flag? false))))

(defn mem-store
  "Create an empty in-memory batch store."
  []
  (MemStore. {}))

(defn mem-store-with-batches
  "Create a store pre-populated with test batches."
  [batch-map]
  (MemStore. batch-map))
