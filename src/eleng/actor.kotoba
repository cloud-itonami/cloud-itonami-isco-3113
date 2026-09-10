(ns eleng.actor
  "ElenGActor — the ISCO-08 3113 electrical engineering technician field
  test and inspection actor as a `langgraph.graph/state-graph` (per
  ADR-2607011000 / CLAUDE.md Actors section). One graph run = one test/inspection
  operation request (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite internal loop;
  checkpointed per superstep so an interrupted run can resume after human
  sign-off.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit           (:ok? true)
                                             +-> :request-approval  (:escalate? true, interrupt-before)
                                             +-> :hold              (:hard? true)
  ```

  The unconditional invariant: the TestAdvisor can never directly commit
  a record or dispatch a robot action the ElenGGovernor refuses — every
  commit-record! call is gated behind `:decide`.

  The escalation is written to the ledger by `:decide`, NOT by
  `:request-approval`. That placement is the whole point: `:request-approval`
  is an `interrupt-before` node, so it does not run until a human resumes the
  thread. Measured 2026-09-10 on `origin/main`, an escalated
  `:flag-electrical-hazard` therefore left the ledger EMPTY — the run stopped
  awaiting sign-off and no fact recorded that it had ever been asked for. A
  hazard that is escalated and then ignored is exactly the case the audit
  trail exists for, and it was the one case that wrote nothing.

  `:request-approval` writes the `:signed-off` fact when it finally does run,
  and `:commit` carries that fact forward as `:approval` — so a commit a human
  approved is distinguishable from one the actor made unattended."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [eleng.advisor :as advisor]
            [eleng.governor :as governor]
            [eleng.ledger :as ledger]
            [eleng.store :as store]))

(defn build-graph
  "Build a compiled ElenGActor graph. `store` implements
  `eleng.store/Store`. `advisor` implements `eleng.advisor/Advisor`
  (defaults to `mock-advisor`). `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         ;; seeded by `run-request!` and checkpointed with the rest of the
         ;; state, so the `:signed-off` fact can name the thread a human
         ;; actually resumed. Graph nodes are handed state only, never the
         ;; run config, so this is the one honest way to have it in the ledger.
         :thread-id   {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [request proposal verdict]}]
                     (let [disposition (cond
                                         (:hard? verdict) :hold
                                         (:escalate? verdict) :request-approval
                                         :else :commit)]
                       ;; Written here, before the interrupt, so that a run
                       ;; which is never approved still leaves the escalation
                       ;; on the record.
                       (when (= :request-approval disposition)
                         (store/append-ledger!
                          store
                          (ledger/escalated {:request request
                                             :proposal proposal
                                             :verdict verdict})))
                       {:disposition disposition})))
      (g/add-node :request-approval
                   (fn [{:keys [request proposal thread-id]}]
                     ;; Reached only on resume: resuming the thread IS the
                     ;; human approval (see `approve!`).
                     (let [fact (ledger/signed-off {:request request
                                                    :proposal proposal
                                                    :thread-id thread-id})]
                       (store/append-ledger! store fact)
                       {:approval fact
                        :audit [{:node :request-approval :approval fact}]})))
      (g/add-node :commit
                   (fn [{:keys [request proposal approval]}]
                     (let [record {:project-id (:project-id request)
                                    :op (:op proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger!
                        store
                        (ledger/committed {:record record :approval approval}))
                       {:record record
                        :audit [{:node :commit :record record :approval approval}]})))
      (g/add-node :hold
                   (fn [{:keys [request proposal verdict]}]
                     (store/append-ledger!
                      store
                      (ledger/held {:request request :proposal proposal :verdict verdict}))
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one test/inspection operation request to completion or interrupt.
  `thread-id` scopes checkpointing for resume after human approval. Returns
  the full run result: `{:state .. :events .. :status :done|:interrupted :frontier ..}`."
  [graph request context thread-id]
  (g/run* graph
          {:request request :context context :thread-id thread-id}
          {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of resuming
  the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
