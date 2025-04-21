(ns shopping-list.module-test
  (:require
   [shopping-list.module :as sut]
   [lazytest.core :as lt :refer [defdescribe describe expect it expect-it
                                 before before-each after after-each around]]
   [com.rpl.rama :as r]
   [com.rpl.rama.path :as rp]
   [com.rpl.rama.test :as rtest]
   [clojure.core.match :refer [match]]
   [clj-ulid :as ulid]))

(defn launch! [ipc]
  (let [threads (+ (rand-int 7) 2)
        tasks 8
        workers 2]
    (println {:msg "in my launch-module!" :data {:threads threads :tasks tasks :workers workers}})
    (rtest/launch-module! ipc sut/Module {:tasks tasks :threads threads
                                          :workers workers})
    (r/get-module-name sut/Module)))

(def ipc (atom nil))

(defn gen-list-id []
  (ulid/ulid))

(defn gen-item-id []
  (ulid/ulid))

#_(defn test-cluster [f]
    (reset! ipc (rtest/create-ipc))
    (launch! @ipc)
    (f)
    (.close @ipc))

#_(t/use-fixtures :each test-cluster)

(defn depot [ipc name]
  (r/foreign-depot ipc (r/get-module-name sut/Module) name))

(defn pstate [ipc name]
  (r/foreign-pstate ipc (r/get-module-name sut/Module) name))

(defn query [ipc name]
  (r/foreign-query ipc (r/get-module-name sut/Module) name))

(defdescribe module-test
  (let [ipc (volatile! nil)]
    (describe "shopping list"
              {:context [(around [f]
                                 (println "before")
                                 (vreset! ipc (rtest/create-ipc))
                                 (launch! @ipc)
                                 (f)
                                 (println "after")
                                 (.close @ipc))]}
              (it "should create a shopping list"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        shopping-lists (pstate @ipc "$$shopping-lists")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id) "Hadil's List" "Hadil"))]
                    (expect
                     (= {:id list-id
                         :name "Hadil's List"
                         :author "Hadil"
                         :subscribers #{}
                         :items []}
                        (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)))))
              (it "should modify an existing shopping list"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        shopping-lists (pstate @ipc "$$shopping-lists")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id) "Hadil's List" "Hadil"))]

                    (r/foreign-append!
                     shopping-list-depot
                     (sut/->ShoppingListEdits list-id [(sut/name-edit "Julia's List") (sut/author-edit "Julia")]))
                    (expect
                     (= {:id list-id
                         :name "Julia's List"
                         :author "Julia"
                         :subscribers #{}
                         :items []}
                        (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)))
                    (r/foreign-append!
                     shopping-list-depot
                     (sut/->AddSubscriber list-id "Hadil"))
                    (r/foreign-append!
                     shopping-list-depot
                     (sut/add-subscriber list-id "Emilee"))
                    (expect
                     (= {:id list-id
                         :name "Julia's List"
                         :author "Julia"
                         :subscribers #{"Emilee" "Hadil"}
                         :items []}
                        (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)))
                    (r/foreign-append!
                     shopping-list-depot
                     (sut/remove-subecriber list-id "Emilee"))
                    (expect
                     (= {:id list-id
                         :name "Julia's List"
                         :author "Julia"
                         :subscribers #{"Hadil"}
                         :items []}
                        (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)))))
              (it "should delete a shopping list"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        shopping-lists (pstate @ipc "$$shopping-lists")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id) "Hadil's List" "Hadil"))]
                    (r/foreign-append!
                     shopping-list-depot
                     (sut/->DeleteShoppingList list-id))
                    (expect
                     (= [nil] (r/foreign-select [(rp/keypath list-id)] shopping-lists))))))

    (describe "item"
              {:context [(around [f]
                                 (println "before")
                                 (vreset! ipc (rtest/create-ipc))
                                 (launch! @ipc)
                                 (f)
                                 (println "after")
                                 (.close @ipc))]}
              (it "should not create an item if the shopping list does not exist"
                  (let [item-depot (depot @ipc "*item-depot")
                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) (gen-list-id)))]

                    (expect
                     (nil? item-id))))
              (it "should create an item for an existing shopping list"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        shopping-lists (pstate @ipc "$$shopping-lists")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))
                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) list-id "Food" "Delicious" 5))]
                    (expect
                     (= {:list-id list-id
                         :id item-id
                         :description "Food"
                         :notes "Delicious"
                         :quantity 5
                         :tags #{}}
                        (r/foreign-select-one [(rp/keypath item-id)] items)))
                    (expect
                     (= {:id list-id
                         :name nil
                         :author nil
                         :subscribers #{}
                         :items [item-id]}
                        (r/foreign-select-one [(rp/keypath list-id)] shopping-lists)))))
              (it "should modify an existing item"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))

                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) list-id "Food" "Delicious" 5))

                        {status "shopping-list"} (r/foreign-append!
                                                  item-depot
                                                  (sut/->ItemEdits item-id [(sut/description-edit "More Food")
                                                                            (sut/quantity-edit 10)]))]
                    (expect (#{:success} status))
                    (expect
                     (= {:list-id list-id
                         :id item-id
                         :description "More Food"
                         :notes "Delicious"
                         :quantity 10
                         :tags #{}}
                        (r/foreign-select-one [(rp/keypath item-id)] items)))))
              (it "will not modify an item that doesn't exist"
                  (let [item-depot (depot @ipc "*item-depot")]
                    (expect
                     (empty? (r/foreign-append! item-depot
                                                (sut/->ItemEdits (gen-item-id) [(sut/description-edit "won't work")]))))))
              (it "should add and remove tags from an existing item"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))
                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) list-id "Food" "Delicious" 5))]

                    (expect (#{:success} (get (r/foreign-append!
                                               item-depot
                                               (sut/->AddTags item-id ["#costco" "#whole_foods"]))
                                              "shopping-list")))
                    (expect
                     (= {:list-id list-id
                         :id item-id
                         :description "Food"
                         :notes "Delicious"
                         :quantity 5
                         :tags #{"#whole_foods" "#costco"}}
                        (r/foreign-select-one [(rp/keypath item-id)] items)))
                    (expect (#{:success} (get (r/foreign-append!
                                               item-depot
                                               (sut/->RemoveTags item-id ["#costco"]))
                                              "shopping-list")))
                    (expect
                     (= {:list-id list-id
                         :id item-id
                         :description "Food"
                         :notes "Delicious"
                         :quantity 5
                         :tags #{"#whole_foods"}}
                        (r/foreign-select-one [(rp/keypath item-id)] items)))))
              (it "should not add or remove tags from an item that doesn't exist"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))
                        item-id (gen-item-id)]

                    (expect (empty? (r/foreign-append!
                                     item-depot
                                     (sut/->AddTags item-id ["#costco" "#whole_foods"]))))

                    (expect (empty? (r/foreign-append!
                                     item-depot
                                     (sut/->RemoveTags item-id ["#costco"]))))))
              (it "should update when the item was purchased for an existing item"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))

                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) list-id))]

                    (expect (#{:success} (get (r/foreign-append!
                                               item-depot
                                               (sut/->PurchasedOn item-id "2025-01-01"))
                                              "shopping-list")))
                    (expect
                     (= "2025-01-01"
                        (r/foreign-select-one
                         [(rp/keypath item-id :last-purchased)] items)))))
              (it "should not update purchase date for an item that doesn't exist"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/map->ShoppingList
                                                    {:id (gen-list-id)}))
                        item-id (gen-item-id)]

                    (expect (empty? (r/foreign-append!
                                     item-depot
                                     (sut/->PurchasedOn item-id "2025-01-01"))))))
              (it "should delete an item"
                  (let [shopping-list-depot (depot @ipc "*shopping-list-depot")
                        item-depot (depot @ipc "*item-depot")
                        items (pstate @ipc "$$items")
                        tags (pstate @ipc "$$tags")
                        {list-id "shopping-list"} (r/foreign-append!
                                                   shopping-list-depot
                                                   (sut/create-shopping-list (gen-list-id)))
                        {item-id "shopping-list"} (r/foreign-append!
                                                   item-depot
                                                   (sut/create-item (gen-item-id) list-id))]
                    (r/foreign-append! item-depot
                                       (sut/->AddTags item-id ["#costco" "#whole_foods"]))
                    (expect
                     (r/foreign-select [(rp/keypath item-id) rp/NONE?] items))
                    (expect
                     (r/foreign-select [(rp/keypath "#costco") rp/NONE?] tags))
                    (expect
                     (r/foreign-select [(rp/keypath "#whole_foods") rp/NONE?] tags)))))))
