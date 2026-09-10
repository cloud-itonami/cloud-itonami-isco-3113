(ns eleng.operation
  "The declared operation vocabulary for the ISCO-08 3113 electrical
  engineering technician actor — the closed set of operations this occupation
  is allowed to perform, and what each one costs in hazard terms.

  This exists because the Advisor reads an `:op` straight out of the request
  (`eleng.advisor/infer` is a stand-in for what an LLM would extract from free
  text). Without a declared vocabulary, ANY keyword is a valid operation:
  measured 2026-09-10 against `origin/main`, a request carrying
  `:op :decommission-the-substation` was proposed at confidence 0.95, passed
  the governor, and committed a record — an operation nobody ever declared,
  executed unattended, on a live electrical installation.

  So the vocabulary is the authority, not the request. `eleng.governor` treats
  an undeclared operation as a HARD violation (`:hold`, never overridable):
  the actor refuses to act on an operation it cannot name.

  Each entry also carries whether the operation requires human sign-off. That
  is not a policy knob — it is this repo's README stating that
  `:high`/`:safety-critical` actions, *such as electrical hazard escalation or
  site access approval*, require human sign-off. Encoding it here is what makes
  that sentence testable.")

(def catalogue
  "op -> spec. `:escalates?` true means the operation ALWAYS routes to
  human sign-off regardless of advisor confidence."
  {:draft-test-record
   {:label      "record electrical test / measurement data"
    :writes     :record
    :hazard     :none
    :escalates? false}

   :log-inspection-data
   {:label      "log inspection findings against an installation"
    :writes     :record
    :hazard     :none
    :escalates? false}

   :flag-electrical-hazard
   {:label      "flag an electrical safety hazard"
    :writes     :record
    :hazard     :electrical
    :escalates? true
    :why        "README robotics premise: electrical hazards always require human sign-off"}

   :schedule-site-visit
   {:label      "schedule a site visit (site access approval)"
    :writes     :record
    :hazard     :site-access
    :escalates? true
    :why        "README robotics premise: site access approval requires human sign-off"}})

(def declared-ops
  "Every operation this occupation declares, in a stable order."
  (into (sorted-set) (keys catalogue)))

(defn spec
  "The spec for `op`, or nil when the operation was never declared."
  [op]
  (get catalogue op))

(defn declared?
  "Has this occupation declared `op`? An undeclared operation is a hard
  governor violation — see `eleng.governor/hard-violations`."
  [op]
  (contains? catalogue op))

(defn escalates?
  "Does `op` always require human sign-off? Undeclared operations are not
  reported as escalating — they are refused outright, which is a stronger
  outcome, and reporting them here would let a caller route an unknown
  operation to approval instead of to `:hold`."
  [op]
  (boolean (:escalates? (spec op))))

(defn hazard
  "The hazard class `op` carries, or `:unknown` when undeclared."
  [op]
  (:hazard (spec op) :unknown))

(defn label
  [op]
  (:label (spec op) "undeclared operation"))

(defn self-check
  "Returns the COUNT of specs that are internally inconsistent, with the
  offending ops. A count rather than a boolean, deliberately: a boolean
  cannot tell one regression apart from a wholly broken catalogue."
  []
  (let [problems
        (for [[op {:keys [label writes hazard escalates? why]}] catalogue
              :let [bad (cond-> []
                          (not (string? label))          (conj :label-not-a-string)
                          (not= :record writes)          (conj :unknown-write-target)
                          (not (keyword? hazard))        (conj :hazard-not-a-keyword)
                          (not (boolean? escalates?))    (conj :escalates-not-a-boolean)
                          ;; an escalating op must say why: the sign-off
                          ;; requirement is a quotation from the README, and an
                          ;; unattributed one is a policy knob someone can turn.
                          (and escalates? (not (string? why))) (conj :escalation-without-a-reason)
                          ;; ... and a non-escalating op must carry no hazard.
                          (and (not escalates?) (not= :none hazard)) (conj :hazard-without-sign-off))]
              :when (seq bad)]
          [op bad])]
    {:count (count problems) :problems (vec problems)}))
