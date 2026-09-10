(ns eleng.governor
  "ElenGGovernor — the independent safety/traceability layer for
  the ISCO-08 3113 electrical engineering technician field test and inspection
  actor. Wired as its own `:govern` node in `eleng.actor`'s StateGraph,
  downstream of `:advise` — the Advisor has no notion of project provenance or
  electrical-hazard risk, so this MUST be a separate system able to
  reject a proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. project provenance   — the request's project must be registered.
    2. no-actuation         — proposal :effect must be :propose.
    3. declared operation   — the :op must be one this occupation declared in
                              `eleng.operation/catalogue`. Measured 2026-09-10
                              on `origin/main`, an undeclared
                              `:op :decommission-the-substation` committed a
                              record unattended at confidence 0.95, because the
                              advisor reads :op straight out of the request and
                              nothing downstream asked whether it was an
                              operation at all. Refusing is deliberately
                              stronger than escalating: an operation nobody
                              declared is not one a human should be asked to
                              approve under time pressure.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off), each one a
  restatement of this repo's README:
    4. an operation whose spec says it always requires sign-off — electrical
       hazard escalation and site access approval (`eleng.operation/escalates?`).
    5. `:stake :high` — the README requires human sign-off for `:high` and
       `:safety-critical` actions. Measured 2026-09-10, a `:high`-stake
       `:schedule-site-visit` ran to completion unattended, because escalation
       keyed on the op alone and :high stake produced confidence 0.7, above the
       floor.
    6. low confidence (< `confidence-floor`).

  The verdict carries `:escalation-reasons` so the ledger can record WHY
  sign-off was demanded, not merely that it was."
  (:require [eleng.store :as store]
            [eleng.operation :as operation]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [proposal]} project-record]
  (cond-> []
    (nil? project-record)
    (conj {:rule :no-project :detail "未登録 project"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (not (operation/declared? (:op proposal)))
    (conj {:rule :undeclared-operation
           :detail (str "宣言されていない操作: " (pr-str (:op proposal))
                        "。宣言済みは " (pr-str (vec operation/declared-ops)))})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `eleng.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool
    :escalation-reasons [...]}`. `:escalation-reasons` is empty when the
  proposal is refused outright — a hard violation is not a thing a human is
  asked to sign off on."
  [request context proposal store]
  (let [project-record (store/project store (:project-id request))
        hard (hard-violations {:proposal proposal} project-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (operation/escalates? (:op proposal))
        high-stake? (= :high (:stake proposal))
        reasons (cond-> []
                  risky-op?   (conj {:rule :operation-requires-sign-off
                                     :op (:op proposal)
                                     :hazard (operation/hazard (:op proposal))})
                  high-stake? (conj {:rule :high-stake})
                  low?        (conj {:rule :low-confidence :confidence conf}))]
    {:ok? (and (not hard?) (empty? reasons))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (boolean (seq reasons)))
     :escalation-reasons (if hard? [] reasons)}))
