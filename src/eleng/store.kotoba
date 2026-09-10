(ns eleng.store
  "SSoT for the ISCO-08 3113 electrical engineering technician field test
  and inspection actor. Store is a protocol injected into the `eleng.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep backend; a
  Datomic/kotoba-server-backed implementation can be swapped in without
  touching the actor or governor (itonami actor pattern, per
  ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    project  — a registered electrical site/installation (:project-id, :name, :location)
    record   — a committed test/inspection record under a project
               (electrical test data, inspection log, site visit note) —
               written ONLY via commit-record!, never mutated in place
    ledger   — an append-only audit trail of every proposal/verdict/
               disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (project [s project-id])
  (records-of [s project-id])
  (ledger [s])
  (register-project! [s project])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (records-of [_ project-id] (filter #(= project-id (:project-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-project! [s project]
    (swap! a assoc-in [:projects (:project-id project)] project) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:projects {} :records [] :ledger []} seed)))))
