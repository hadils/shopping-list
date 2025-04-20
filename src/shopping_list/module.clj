(ns shopping-list.module
  (:require
   [com.rpl.rama :as r]
   [com.rpl.rama.path :as rp]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops])
  (:import
   (java.util UUID)))

(def ListID UUID)
(def ItemID UUID)

(defrecord ShoppingList [list-id name author])
(defrecord ShoppingListEdit [field value])
(defrecord ShoppingListEdits [list-id edits])
(defrecord AddSubscriber [list-id subscriber])
(defrecord RemoveSubscriber [list-id subscriber])
(defrecord DeleteShoppingList [list-id])

(defn name-edit [value]
  (->ShoppingListEdit :name value))

(defn author-edit [value]
  (->ShoppingListEdit :author value))

(defn add-subscriber [list-id subscriber]
  (->AddSubscriber list-id subscriber))

(defn remove-subecriber [list-id subscriber]
  (->RemoveSubscriber list-id subscriber))

(defrecord Item [item-id list-id description notes quantity tags last-bought])

(r/defmodule Module [setup topologies]
  (r/declare-depot setup *shopping-list-depot (r/hash-by :list-id))
  (r/declare-depot setup *item-depot (r/hash-by :item-id))

  (let [s (r/stream-topology topologies "shopping-list")]
    (r/declare-pstate s $$shopping-lists {ListID
                                          (r/fixed-keys-schema
                                           {:list-id ListID
                                            :name String
                                            :author String
                                            :subscribers (r/set-schema String)})})

    (r/declare-pstate s $$items {ItemID
                                 (r/fixed-keys-schema
                                  {:item-id ItemID
                                   :description String
                                   :notes String
                                   :quantity Long
                                   :tags (r/set-schema String)
                                   :last-bought String
                                   :list-id ListID})})

    (r/declare-pstate s $$tags {String (r/set-schema ItemID {:subindex? true})})
    (r/<<sources s
      (r/source> *shopping-list-depot :> *list)
      (println "shopping-list" *list)
      (r/<<subsource *list
                     (r/case> ShoppingList)
                     (identity *list :> {:keys [*list-id *name *author]})
                     (r/local-transform> [(rp/keypath *list-id)
                                          (rp/termval {:list-id *list-id
                                                       :name *name
                                                       :author *author
                                                       :subscribers #{}})] $$shopping-lists)
                     (r/ack-return> *list-id)

                     (r/case> DeleteShoppingList)
                     (identity *list :> {:keys [*list-id]})
                     (r/local-transform> [(rp/keypath *list-id)
                                          r/NONE>] $$shopping-lists)

                     (r/case> ShoppingListEdits)
                     (identity *list :> {:keys [*list-id *edits]})
                     (ops/explode *edits :> {:keys [*field *value]})
                     (r/local-transform> [(rp/keypath *list-id *field) (rp/termval *value)] $$shopping-lists)

                     (r/case> AddSubscriber)
                     (identity *list :> {:keys [*list-id *subscriber]})
                     (r/local-transform> [(rp/keypath *list-id :subscribers) rp/NONE-ELEM (rp/termval *subscriber)] $$shopping-lists)

                     (r/case> RemoveSubscriber)
                     (identity *list :> {:keys [*list-id *subscriber]})
                     (r/local-transform> [(rp/keypath *list-id :subscribers) (rp/set-elem *subscriber) r/NONE>] $$shopping-lists)))))
