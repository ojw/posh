(ns posh.clj.datomic-test
  (:require [clojure.test     :as test
              :refer [is deftest testing]]
            [datomic.api      :as d]
            [posh.clj.datomic :as db]
            [posh.lib.ratom   :as r]
            [posh.lib.util :as u
              :refer [debug prl]])
  (:import posh.clj.datomic.PoshableConnection))

#_(do (require '[clojure.tools.namespace.repl :refer [refresh]])
           (refresh)
           (set! *warn-on-reflection* true)
           (reset! posh.lib.util/debug? true)
(eval `(do (clojure.test/run-tests 'posh.lib.ratom-test)
           (clojure.test/run-tests 'posh.clj.datascript-test)
           (clojure.test/run-tests 'posh.clj.datomic-test))))

(def default-partition :db.part/default)
(defn tempid [] (d/tempid default-partition))

(defn install-partition [part]
  (let [id (d/tempid :db.part/db)]
    [{:db/id    id
      :db/ident part}
     [:db/add               :db.part/db
      :db.install/partition id]]))

(defmacro with-conn [sym & body]
  `(let [uri# "datomic:mem://test"]
     (try (d/create-database uri#)
          (let [~sym (d/connect uri#)]
            (try ~@body
              (finally (d/release ~sym))))
          (finally (d/delete-database uri#)))))

(defn transact-schemas!
  "This is used because, perhaps very strangely, schema changes to Datomic happen
   asynchronously."
  [conn schemas]
  (let [txn-report (db/transact! conn
                     (->> schemas
                          (map #(assoc % :db/id (d/tempid :db.part/db)
                                         :db.install/_attribute :db.part/db))))
        txn-id     (-> txn-report :tx-data first (get 3))
        _ #_(deref (d/sync (db/->conn conn) (java.util.Date. (System/currentTimeMillis))) 500 nil)
            (deref (d/sync-schema (db/->conn conn) (inc txn-id)) 500 nil)] ; frustratingly, doesn't even work with un-`inc`ed txn-id
    txn-report))

(defn with-setup [schemas f]
  (with-conn conn*
    (let [poshed (db/posh! conn*) ; This performs a `with-meta` so the result is needed
          conn   (-> poshed :conns :conn0) ; Has the necessary meta ; TODO simplify this
          _      (is (instance? PoshableConnection conn))]
      (try (let [txn-report (db/transact! conn (install-partition default-partition))
                 txn-report (transact-schemas! conn schemas)]
             (f conn))
        (finally (db/stop conn)))))) ; TODO `unposh!`

(deftest basic-test
  (with-setup
    [{:db/ident       :test/attr
      :db/valueType   :db.type/string
      :db/cardinality :db.cardinality/one}]
    (fn [conn]
      (let [sub          (db/q [:find '?e :where ['?e :test/attr]] conn)
            sub-no-deref (db/q [:find '?e :where ['?e :test/attr]] conn)
            _ (is (= @sub #{}))
            notified (atom 0)
            _ (r/add-eager-watch sub :k (fn [_ _ _ _] (swap! notified inc)))
            notified-no-deref (atom 0)
            _ (r/add-eager-watch sub-no-deref :k-no-deref (fn [_ _ _ _] (swap! notified-no-deref inc)))]
      (testing "Listeners are notified correctly"
        (db/transact! conn
          [{:db/id     (tempid)
            :test/attr "Abcde"}])
        (do @sub @sub @sub @sub)
        (is (= @sub
               @(db/q [:find '?e
                       :where ['?e :test/attr]]
                      conn)
               (d/q [:find '?e
                     :where ['?e :test/attr]]
                     (db/db* conn))))
        (is (= @notified 1))
        (is (= @notified-no-deref 1))
        (db/transact! conn
          [{:db/id     (tempid)
            :test/attr "Fghijk"}])
        (do @sub @sub @sub @sub @sub)
        (is (= @notified 2))
        (is (= @notified-no-deref 2)))
      (testing "Remove-watch happens correctly"
        (remove-watch sub :k)
        (remove-watch sub :k-no-deref)
        (db/transact! conn
          [{:db/id     (tempid)
            :test/attr "Lmnop"}])
        (do @sub @sub @sub @sub @sub @sub)
        (is (= @notified 2))
        (is (= @notified-no-deref 2)))))))
