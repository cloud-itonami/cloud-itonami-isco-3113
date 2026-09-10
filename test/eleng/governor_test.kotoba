(ns eleng.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [eleng.store :as store]
            [eleng.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :name "Main Electrical Distribution" :location "Building A"})
    st))

(deftest ok-on-clean-test-record
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-on-inspection-logging
  (let [st (fresh-store)
        proposal {:op :log-inspection-data :effect :propose :confidence 0.85 :stake :medium}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest site-visit-scheduling-requires-sign-off
  ;; CHANGED 2026-09-10. This test previously asserted the opposite — that
  ;; scheduling a site visit needs no human sign-off — and it passed, because
  ;; the governor escalated on `:flag-electrical-hazard` alone. The README
  ;; says `:high`/`:safety-critical` actions, "such as electrical hazard
  ;; escalation, or site access approval", require human sign-off. Measured on
  ;; `origin/main`, a :high-stake :schedule-site-visit ran to completion
  ;; unattended. The old assertion was pinning the defect, so it is flipped
  ;; here rather than deleted: site access approval is site access approval.
  (let [st (fresh-store)
        proposal {:op :schedule-site-visit :effect :propose :confidence 0.8 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (not (:ok? v)))
    (is (not (:hard? v)) "site access is escalated for sign-off, not refused")
    (is (:escalate? v))
    (is (some #(= :operation-requires-sign-off (:rule %)) (:escalation-reasons v)))))

(deftest hard-on-undeclared-operation
  ;; Measured 2026-09-10 on `origin/main`: this proposal committed a record,
  ;; unattended, at confidence 0.95.
  (let [st (fresh-store)
        proposal {:op :decommission-the-substation :effect :propose :confidence 0.95 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v))
    (is (not (:ok? v)))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))
    (testing "refused outright, never handed to a human to approve"
      (is (not (:escalate? v)))
      (is (empty? (:escalation-reasons v))))))

(deftest hard-on-missing-operation
  (let [st (fresh-store)
        proposal {:op nil :effect :propose :confidence 0.0 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest escalates-on-high-stake
  ;; The README requires sign-off for :high/:safety-critical actions, not only
  ;; for the two operations it gives as examples.
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))
    (is (some #(= :high-stake (:rule %)) (:escalation-reasons v)))))

(deftest confidence-floor-is-a-strict-inequality
  ;; The rule is "< confidence-floor". Both sides of the boundary AND the
  ;; boundary itself, so that flipping < to <= is visible.
  (let [st (fresh-store)
        at (fn [conf]
             (governor/check {:project-id "proj-1"} {}
                             {:op :draft-test-record :effect :propose
                              :confidence conf :stake :low}
                             st))]
    (testing "below the floor escalates"
      (is (:escalate? (at 0.59))))
    (testing "exactly at the floor does NOT escalate"
      (is (not (:escalate? (at governor/confidence-floor))))
      (is (:ok? (at governor/confidence-floor))))
    (testing "above the floor does not escalate"
      (is (not (:escalate? (at 0.61)))))))

(deftest escalation-reasons-say-why-and-accumulate
  (let [st (fresh-store)
        proposal {:op :flag-electrical-hazard :effect :propose :confidence 0.1 :stake :high}
        v (governor/check {:project-id "proj-1"} {} proposal st)
        rules (set (map :rule (:escalation-reasons v)))]
    (is (:escalate? v))
    (is (= #{:operation-requires-sign-off :high-stake :low-confidence} rules)
        "all three independent grounds are recorded, not just the first")))

(deftest hard-on-unregistered-project
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:project-id "no-such-project"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-project (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :direct-write :confidence 0.9 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-on-electrical-hazard
  (let [st (fresh-store)
        proposal {:op :flag-electrical-hazard :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-low-confidence
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.2 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest store-records-and-ledger-append-only
  (let [st (fresh-store)]
    (store/commit-record! st {:project-id "proj-1" :op :draft-test-record})
    (store/append-ledger! st {:disposition :commit})
    (is (= 1 (count (store/records-of st "proj-1"))))
    (is (= 1 (count (store/ledger st))))))
