(ns eleng.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [eleng.store :as store]
            [eleng.advisor :as advisor]
            [eleng.ledger :as ledger]
            [eleng.actor :as actor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :name "Main Electrical Distribution" :location "Building A"})
    st))

(deftest run-request-accepts-test-record
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "proj-1" :op :draft-test-record :stake :low}
                                  {}
                                  "thread-1")]
    (is (= :done (:status result)))
    (is (= 1 (count (store/records-of st "proj-1"))))))

(deftest run-request-escalates-electrical-hazard
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "proj-1" :op :flag-electrical-hazard :stake :high}
                                  {}
                                  "thread-2")]
    (is (= :interrupted (:status result)))
    (is (empty? (store/records-of st "proj-1")))))

(deftest run-request-rejects-unregistered-project
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "no-such-project" :op :draft-test-record :stake :low}
                                  {}
                                  "thread-3")]
    (is (= :done (:status result)))
    (is (empty? (store/records-of st "no-such-project")))))

(deftest approve-resumes-and-commits
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result1 (actor/run-request! graph
                                   {:project-id "proj-1" :op :flag-electrical-hazard :stake :high}
                                   {}
                                   "thread-4")]
    (is (= :interrupted (:status result1)))
    (is (empty? (store/records-of st "proj-1")))

    ;; approve and resume
    (let [result2 (actor/approve! graph "thread-4")]
      (is (= :done (:status result2)))
      (is (= 1 (count (store/records-of st "proj-1")))))))

(deftest audit-ledger-records-all-events
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        _ (actor/run-request! graph
                             {:project-id "proj-1" :op :draft-test-record :stake :low}
                             {}
                             "thread-5")]
    (is (>= (count (store/ledger st)) 1))))

(deftest escalation-is-on-the-record-before-anyone-approves
  ;; Measured 2026-09-10 on `origin/main`: this run interrupted for sign-off
  ;; and left the ledger EMPTY. A hazard that is escalated and then never
  ;; approved is precisely what the audit trail is for, and it was the one
  ;; case that wrote nothing. The assertion is deliberately made BEFORE any
  ;; approve! call — the fact has to survive a run nobody ever comes back to.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                   {:project-id "proj-1" :op :flag-electrical-hazard :stake :high}
                                   {}
                                   "thread-esc")
        facts (store/ledger st)]
    (is (= :interrupted (:status result)))
    (is (seq facts) "an escalated hazard must leave audit evidence")
    (let [e (first (filter #(= :escalated (:disposition %)) facts))]
      (is (some? e) "there must be an :escalated fact")
      (is (= :flag-electrical-hazard (:op e)))
      (is (= :electrical (:hazard e)))
      (is (seq (:reasons e)) "the escalation must say why sign-off was demanded"))
    (testing "and no record was written while it waits"
      (is (empty? (store/records-of st "proj-1"))))))

(deftest a-signed-off-commit-is-distinguishable-from-an-unattended-one
  ;; Measured 2026-09-10 on `origin/main`: the two ledgers were identical
  ;; apart from the operation name, so the trail could not answer the only
  ;; question an electrical-safety audit asks of it.
  (let [approved (let [st (fresh-store)
                       g (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
                   (actor/run-request! g {:project-id "proj-1" :op :flag-electrical-hazard :stake :high}
                                       {} "thread-appr")
                   (actor/approve! g "thread-appr")
                   (store/ledger st))
        unattended (let [st (fresh-store)
                         g (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
                     (actor/run-request! g {:project-id "proj-1" :op :draft-test-record :stake :low}
                                         {} "thread-auto")
                     (store/ledger st))
        commit-of (fn [facts] (first (filter #(= :commit (:disposition %)) facts)))]
    (is (ledger/attested? (commit-of approved)))
    (is (not (ledger/attested? (commit-of unattended))))
    (testing "the sign-off names the thread a human actually resumed"
      (is (= "thread-appr" (get-in (commit-of approved) [:approval :thread-id]))))
    (testing "and the approved trail records escalation, sign-off and commit"
      (is (= [:escalated :signed-off :commit] (mapv :disposition approved))))))

(deftest a-completed-run-audits-clean-in-both-directions
  (letfn [(trail [req tid approve?]
            (let [st (fresh-store)
                  g (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
              (actor/run-request! g req {} tid)
              (when approve? (actor/approve! g tid))
              (store/ledger st)))]
    (testing "unattended routine commit"
      (is (zero? (:count (ledger/audit
                          (trail {:project-id "proj-1" :op :draft-test-record :stake :low}
                                 "a1" false))))))
    (testing "escalated then approved"
      (is (zero? (:count (ledger/audit
                          (trail {:project-id "proj-1" :op :flag-electrical-hazard :stake :high}
                                 "a2" true))))))
    (testing "refused"
      (is (zero? (:count (ledger/audit
                          (trail {:project-id "nope" :op :draft-test-record :stake :low}
                                 "a3" false))))))))

(deftest an-undeclared-operation-is-refused-and-the-refusal-is-recorded
  ;; Measured 2026-09-10 on `origin/main`: this committed a record.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                   {:project-id "proj-1" :op :decommission-the-substation :stake :low}
                                   {}
                                   "thread-undeclared")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])) "no record may be written")
    (is (empty? (store/records-of st "proj-1")))
    (let [h (first (filter #(= :hold (:disposition %)) (store/ledger st)))]
      (is (some? h) "the refusal must be recorded, not merely performed")
      (is (some #(= :undeclared-operation (:rule %)) (:violations h))
          "and it must name the rule it refused under"))))

(deftest a-request-naming-no-operation-does-not-crash-the-advisor
  ;; Measured 2026-09-10 on `origin/main`: this threw a NullPointerException
  ;; out of the advisor before the governor ever saw the proposal.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                   {:project-id "proj-1" :stake :low}
                                   {}
                                   "thread-nil-op")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (zero? (get-in result [:state :proposal :confidence]))
        "an operation the advisor cannot name must not be its most confident one")))
