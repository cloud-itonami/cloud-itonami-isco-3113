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

(deftest ok-on-site-visit-scheduling
  (let [st (fresh-store)
        proposal {:op :schedule-site-visit :effect :propose :confidence 0.8 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

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
