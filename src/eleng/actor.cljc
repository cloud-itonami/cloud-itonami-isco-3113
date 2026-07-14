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
  commit-record! call is gated behind `:decide`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [eleng.advisor :as advisor]
            [eleng.governor :as governor]
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
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal]}]
                     (let [record {:project-id (:project-id request)
                                    :op (:op proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger! store {:disposition :commit :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
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
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of resuming
  the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
