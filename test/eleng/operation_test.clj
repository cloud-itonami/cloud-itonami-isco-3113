(ns eleng.operation-test
  "The declared operation vocabulary is the authority on what this occupation
  may do. These tests pin that the catalogue is internally consistent and that
  `declared?` actually closes the set — measured 2026-09-10, an undeclared
  operation committed a record unattended at confidence 0.95."
  (:require [clojure.test :refer [deftest is testing]]
            [eleng.operation :as operation]))

(deftest catalogue-is-internally-consistent
  ;; a COUNT, not a boolean: a boolean cannot tell one regression apart from a
  ;; catalogue that has stopped being a catalogue.
  (let [{:keys [count problems]} (operation/self-check)]
    (is (zero? count) (str "inconsistent specs: " (pr-str problems)))))

(deftest the-four-declared-operations
  (is (= #{:draft-test-record :log-inspection-data
           :flag-electrical-hazard :schedule-site-visit}
         (set operation/declared-ops)))
  (doseq [op operation/declared-ops]
    (is (operation/declared? op) (str op " is in declared-ops but not declared?"))))

(deftest the-set-is-closed
  (testing "an operation nobody declared is not declared, however plausible"
    (is (not (operation/declared? :decommission-the-substation)))
    (is (not (operation/declared? :draft-test-records)))   ; near-miss plural
    (is (not (operation/declared? nil)))
    (is (not (operation/declared? "draft-test-record")))))  ; string, not keyword

(deftest sign-off-is-required-where-the-readme-says-it-is
  (testing "electrical hazard escalation"
    (is (operation/escalates? :flag-electrical-hazard))
    (is (= :electrical (operation/hazard :flag-electrical-hazard))))
  (testing "site access approval"
    (is (operation/escalates? :schedule-site-visit))
    (is (= :site-access (operation/hazard :schedule-site-visit))))
  (testing "routine record-keeping carries no hazard and needs no sign-off"
    (is (not (operation/escalates? :draft-test-record)))
    (is (not (operation/escalates? :log-inspection-data)))
    (is (= :none (operation/hazard :draft-test-record)))))

(deftest an-undeclared-operation-does-not-report-as-escalating
  ;; It must be REFUSED, not routed to a human. Reporting it as escalating
  ;; would let a caller hand an unknown operation to an operator to approve
  ;; under time pressure, which is weaker than refusing outright.
  (is (not (operation/escalates? :decommission-the-substation)))
  (is (not (operation/escalates? nil)))
  (is (= :unknown (operation/hazard :decommission-the-substation))))

(deftest self-check-can-actually-fail
  ;; The guard above is only worth having if it discriminates. Feed it a spec
  ;; that breaks each rule and confirm the count rises for the stated reason.
  (with-redefs [operation/catalogue
                {:bad-escalation {:label "x" :writes :record :hazard :electrical
                                  :escalates? true}          ; no :why
                 :bad-hazard     {:label "y" :writes :record :hazard :electrical
                                  :escalates? false}}]       ; hazard, no sign-off
    (let [{:keys [count problems]} (operation/self-check)
          rules (set (mapcat second problems))]
      (is (= 2 count))
      (is (contains? rules :escalation-without-a-reason))
      (is (contains? rules :hazard-without-sign-off)))))
