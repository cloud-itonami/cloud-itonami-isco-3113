(ns eleng.ledger-test
  "`eleng.ledger/audit` reads an audit trail back and counts what is wrong with
  it. These tests exist in both directions: a well-formed ledger must audit
  clean, and each malformed one must be caught for the reason it is named
  after — a negative test that passes because something else went wrong has
  not discriminated anything."
  (:require [clojure.test :refer [deftest is testing]]
            [eleng.ledger :as ledger]))

(def ^:private escalated
  (ledger/escalated {:request {:project-id "P1"}
                     :proposal {:op :flag-electrical-hazard}
                     :verdict {:confidence 0.7
                               :escalation-reasons [{:rule :high-stake}]}}))

(def ^:private signed-off
  (ledger/signed-off {:request {:project-id "P1"}
                      :proposal {:op :flag-electrical-hazard}
                      :thread-id "T1"}))

(defn- commit-of [op approval]
  (ledger/committed {:record {:project-id "P1" :op op :payload {}}
                     :approval approval}))

(defn- rules [ledger-facts]
  (set (map second (:violations (ledger/audit ledger-facts)))))

(deftest an-unattended-routine-commit-audits-clean
  (let [{:keys [count]} (ledger/audit [(commit-of :draft-test-record nil)])]
    (is (zero? count))))

(deftest a-fully-signed-off-hazard-audits-clean
  (let [{:keys [count violations]}
        (ledger/audit [escalated signed-off (commit-of :flag-electrical-hazard signed-off)])]
    (is (zero? count) (pr-str violations))))

(deftest a-hazard-committed-without-sign-off-is-caught
  ;; the README: no automated advice may escalate an electrical hazard
  ;; "without governor approval and audit evidence".
  (testing "the commit alone"
    (is (contains? (rules [(commit-of :flag-electrical-hazard nil)])
                   :unattested-escalating-commit)))
  (testing "even with the escalation recorded, an unattested commit is caught"
    (is (contains? (rules [escalated (commit-of :flag-electrical-hazard nil)])
                   :unattested-escalating-commit)))
  (testing "site access is held to the same rule as electrical hazard"
    (is (contains? (rules [(commit-of :schedule-site-visit nil)])
                   :unattested-escalating-commit))))

(deftest approval-cannot-be-minted-after-the-fact
  ;; an approval with no escalation before it in the trail means the sign-off
  ;; was recorded for something nobody was asked to sign off on.
  (is (contains? (rules [(commit-of :flag-electrical-hazard signed-off)])
                 :approval-without-escalation))
  (testing "order matters — the escalation must PRECEDE the approval"
    (is (contains? (rules [(commit-of :flag-electrical-hazard signed-off) escalated])
                   :approval-without-escalation))))

(deftest a-hold-must-say-why
  (is (contains? (rules [{:disposition :hold :op :draft-test-record :violations []}])
                 :hold-without-a-reason))
  (testing "a hold carrying its violated rule audits clean"
    (is (zero? (:count (ledger/audit
                        [(ledger/held {:request {:project-id "P1"}
                                       :proposal {:op :draft-test-record}
                                       :verdict {:violations [{:rule :no-project}]}})]))))))

(deftest an-unknown-disposition-is-caught
  (is (contains? (rules [{:disposition :quietly-fine :op :draft-test-record}])
                 :unknown-disposition)))

(deftest audit-counts-rather-than-verdicts
  ;; A boolean could not tell one regression apart from a ledger that had
  ;; stopped being written to. Two independently broken facts must count two.
  (let [{:keys [count]} (ledger/audit [(commit-of :flag-electrical-hazard nil)
                                       (commit-of :schedule-site-visit nil)])]
    (is (= 2 count))))

(deftest an-empty-ledger-is-not-evidence-of-good-behaviour
  ;; It audits clean because there is nothing wrong IN it — which is exactly
  ;; why emptiness is checked at the actor level (see eleng.actor-test), not
  ;; here. Pinned so nobody later reads a clean audit of [] as an all-clear.
  (is (zero? (:count (ledger/audit []))))
  (is (empty? (:violations (ledger/audit [])))))

(deftest attested-distinguishes-the-two-commits
  (is (ledger/attested? (commit-of :flag-electrical-hazard signed-off)))
  (is (not (ledger/attested? (commit-of :draft-test-record nil))))
  (testing "only a commit can be attested"
    (is (not (ledger/attested? signed-off)))
    (is (not (ledger/attested? escalated)))))

(deftest the-escalation-fact-carries-why-not-merely-that
  (is (= :escalated (:disposition escalated)))
  (is (= :electrical (:hazard escalated)))
  (is (= [{:rule :high-stake}] (:reasons escalated)))
  (is (contains? ledger/dispositions (:disposition escalated))))
