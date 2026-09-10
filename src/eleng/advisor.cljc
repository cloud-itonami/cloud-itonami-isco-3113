(ns eleng.advisor
  "TestAdvisor — proposes an electrical test, inspection, or hazard-logging
  operation (record test/measurement data, log inspection findings, flag electrical
  safety hazards, schedule site visits) for a registered project. The advisor is
  swappable: `mock-advisor` (deterministic, default in dev/tests/CI) or
  `llm-advisor` (wraps a real `langchain.model/ChatModel`). Either way the advisor
  ONLY produces a PROPOSAL — it never writes to the store and has no notion of
  project provenance or electrical-hazard risk; `eleng.governor` is the independent
  system that decides whether the proposal may proceed, per the itonami actor pattern.

  A proposal is a map:
    {:op :draft-test-record|:log-inspection-data|:flag-electrical-hazard|:schedule-site-visit
     :effect :propose        ; the advisor NEVER emits a raw store write
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}
  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold. So does a request
  that names no operation, or names one this occupation never declared:
  measured 2026-09-10 on `origin/main`, a request with no `:op` threw a
  NullPointerException out of the advisor before the governor ever saw it, and
  an undeclared `:op` was proposed at confidence 0.95 — the advisor was most
  certain about exactly the operations it understood least."
  (:require [eleng.operation :as operation]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared op/stake
  straight through (a stand-in for what an LLM would extract from free
  text), with a stake-derived confidence.

  An operation this occupation has not declared — including a missing one —
  is proposed at confidence 0.0 rather than crashing or being read straight
  through. The governor still refuses it outright (`:undeclared-operation` is
  a HARD violation); the 0.0 is so that the proposal is never the most
  confident thing in the ledger on its way there."
  [_store {:keys [op stake] :as request}]
  (let [known? (operation/declared? op)
        stake  (or stake :low)]
    {:op op
     :effect :propose
     :stake stake
     :confidence (if known?
                   (case stake :high 0.7 :medium 0.85 :low 0.95 0.0)
                   0.0)
     :rationale (if known?
                  (str "proposed " (clojure.core/name op)
                       " for project " (:project-id request))
                  (str "undeclared operation " (pr-str op)
                       " for project " (:project-id request)))}))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an electrical engineering technician advisor. Given an electrical
   test, inspection, or hazard-reporting operation request, propose an :op,
   an honest :confidence (0.0-1.0), and a :stake (:low/:medium/:high).
   Never fabricate confidence you don't have.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "electrical operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
