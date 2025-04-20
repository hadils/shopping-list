(ns shopping-list.module-test
  (:require
   [shopping-list.module :as sut]
   [clojure.test :as t]
   [com.rpl.rama :as r]
   [com.rpl.rama.path :as rp]
   [com.rpl.rama.test :as rtest]
   [clojure.core.match :refer [match]]))

(defn launch! [ipc]
  (let [threads (+ (rand-int 7) 2)
        tasks 8
        workers 2]
    (println {:msg "in my launch-module!" :data {:threads threads :tasks tasks :workers workers}})
    (rtest/launch-module! ipc sut/Module {:tasks tasks :threads threads
                                          :workers workers})
    (r/get-module-name sut/Module)))

(def ipc (atom nil))

(defn test-cluster [f]
  (reset! ipc (rtest/create-ipc))
  (launch! @ipc)
  (f)
  (.close @ipc))

(t/use-fixtures :each test-cluster)

(defn depot [name]
  (r/foreign-depot @ipc (r/get-module-name sut/Module) name))

(defn pstate [name]
  (r/foreign-pstate @ipc (r/get-module-name sut/Module) name))

(defn query [name]
  (r/foreign-query @ipc (r/get-module-name sut/Module) name))

(t/deftest list-test
  (let [shopping-list-depot (depot "*shopping-list-depot")
        shopping-lists (pstate "$$shopping-lists")

        ack (r/foreign-append! shopping-list-depot
                               (sut/map->ShoppingList
                                {:list-id (random-uuid)
                                 :name "Hadil's List"
                                 :author "Hadil Sabbagh"}))
        {list-id "shopping-list"} ack
        result (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)]
    (t/is (match [result]
                 [{:list-id list-id :name "Hadil's List" :author "Hadil Sabbagh" :subscribers #{}}] true
                 :else false))
    (let [edits (sut/->ShoppingListEdits list-id [(sut/name-edit "Julia's List")
                                                  (sut/author-edit "Julia")])]
      (r/foreign-append! shopping-list-depot edits)
      (t/is (match [(r/foreign-select-one [(rp/keypath list-id)] shopping-lists)]
                   [{:list-id list-id :name "Julia's List" :author "Julia" :subscribers #{}}] true
                   :else false))
      (r/foreign-append! shopping-list-depot (sut/add-subscriber list-id "Hadil"))
      (t/is (match [(r/foreign-select-one [(rp/keypath list-id)] shopping-lists)]
                   [{:list-id list-id :name "Julia's List" :author "Julia" :subscribers #{"Hadil"}}] true
                   :else false))
      (r/foreign-append! shopping-list-depot (sut/remove-subecriber list-id "Hadil"))
      (t/is (match [(r/foreign-select-one [(rp/keypath list-id)] shopping-lists)]
                   [{:list-id list-id :name "Julia's List" :author "Julia" :subscribers #{}}] true
                   :else false))
      (r/foreign-append! shopping-list-depot (sut/->DeleteShoppingList list-id))
      (t/is (nil? (r/foreign-select-one [(rp/keypath list-id)] shopping-lists))))))
