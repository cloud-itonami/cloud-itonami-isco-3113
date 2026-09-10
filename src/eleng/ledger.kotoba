(ns eleng.ledger
  "The shape of the append-only audit trail for the ISCO-08 3113 electrical
  engineering technician actor, and the audit that reads it back.

  `eleng.store` owns *where* ledger facts are kept (`append-ledger!`); this
  namespace owns *what a fact has to say*. It is separate because the two
  defects it exists to close are both defects of what was written, not of
  where it went — measured 2026-09-10 against `origin/main`:

    1. An escalated `:flag-electrical-hazard` run left the ledger EMPTY. The
       run interrupted for human sign-off and stopped, and because only
       `:commit` and `:hold` wrote facts, the escalation itself was never
       recorded. This repo's README says no automated advice may `escalate an
       electrical hazard without governor approval and audit evidence` — the
       escalation was the one event with no audit evidence at all.

    2. A commit that a human had signed off on was byte-identical to a commit
       the actor made unattended. Nothing in the record said a person had
       looked at it, so the ledger could not answer the only question an
       electrical-safety audit asks of it.

  Hence four dispositions rather than two, and `audit` below, which reads a
  ledger back and returns the COUNT of integrity violations."
  (:require [eleng.operation :as operation]))

(def dispositions
  "Every disposition a ledger fact may carry.

    :escalated  — the governor sent the proposal to human sign-off. Written
                  BEFORE the graph interrupts, so it survives a run that is
                  never approved.
    :signed-off — a human resumed the interrupted thread. The act of resuming
                  IS the approval (see `eleng.actor/approve!`).
    :commit     — a record was written.
    :hold       — the proposal was refused; no record was written."
  #{:escalated :signed-off :commit :hold})

(defn- base [disposition m]
  (merge {:disposition disposition} m))

(defn escalated
  "The fact that the governor required human sign-off, and why."
  [{:keys [request proposal verdict]}]
  (base :escalated
        {:project-id (:project-id request)
         :op         (:op proposal)
         :hazard     (operation/hazard (:op proposal))
         :confidence (:confidence verdict)
         :reasons    (vec (:escalation-reasons verdict))}))

(defn signed-off
  "The fact that a human resumed the thread, i.e. approved it."
  [{:keys [request proposal thread-id]}]
  (base :signed-off
        {:project-id (:project-id request)
         :op         (:op proposal)
         :thread-id  thread-id}))

(defn committed
  "The fact that a record was written. `approval` is nil for an unattended
  commit and the `:signed-off` fact for an approved one — this is the field
  that makes the two distinguishable."
  [{:keys [record approval]}]
  (base :commit {:record record :approval approval}))

(defn held
  "The fact that a proposal was refused, carrying the violated rules so the
  ledger says WHY, not merely that something was stopped."
  [{:keys [request proposal verdict]}]
  (base :hold
        {:project-id (:project-id request)
         :op         (:op proposal)
         :violations (vec (:violations verdict))}))

(defn attested?
  "Did this commit fact carry a human sign-off?"
  [fact]
  (and (= :commit (:disposition fact)) (some? (:approval fact))))

(defn audit
  "Read a ledger back and return
  `{:count n :violations [[index rule detail] ...]}` — the COUNT of integrity
  violations, not a verdict. A boolean here could not tell a single regression
  apart from a ledger that had stopped being written to at all, which is
  precisely the failure this namespace exists to detect.

  The invariants, each traceable to the README:

    A. every fact carries a known disposition;
    B. a commit of an operation that always requires sign-off must carry an
       approval — `escalate an electrical hazard without governor approval and
       audit evidence` is what the README forbids;
    C. that approval must be preceded in the ledger by the `:escalated` fact
       that demanded it, so approval cannot be minted after the fact;
    D. a `:hold` must say which rule it violated."
  [facts]
  (let [facts (vec facts)
        escalated-before (fn [i op]
                           (some (fn [j]
                                   (let [f (nth facts j)]
                                     (and (= :escalated (:disposition f))
                                          (= op (:op f)))))
                                 (range 0 i)))
        violations
        (for [[i f] (map-indexed vector facts)
              :let [op (or (:op f) (get-in f [:record :op]))
                    bad (cond-> []
                          (not (contains? dispositions (:disposition f)))
                          (conj [:unknown-disposition (:disposition f)])

                          (and (= :commit (:disposition f))
                               (operation/escalates? op)
                               (not (attested? f)))
                          (conj [:unattested-escalating-commit op])

                          (and (attested? f) (not (escalated-before i op)))
                          (conj [:approval-without-escalation op])

                          (and (= :hold (:disposition f))
                               (empty? (:violations f)))
                          (conj [:hold-without-a-reason op]))]
              b bad]
          (into [i] b))]
    {:count (count violations) :violations (vec violations)}))
