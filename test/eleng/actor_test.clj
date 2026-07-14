(ns eleng.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [eleng.store :as store]
            [eleng.advisor :as advisor]
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
