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

(defrecord Item [item-id list-id description notes quantity])
(defrecord ItemEdit [field value])
(defrecord ItemEdits [item-id edits])
(defrecord PurchasedOn [item-id date])
(defrecord AddTags [item-id tags])
(defrecord RemoveTags [item-id tags])
(defrecord DeleteItem [item-id])

(defn create-item [item-id list-id description notes quantity]
  (->Item item-id list-id description notes quantity))

(defn description-edit [value]
  (->ItemEdit :description value))

(defn notes-edit [value]
  (->ItemEdit :notes value))

(defn quantity-edit [value]
  (->ItemEdit :quantity value))

(defn add-tag [item-id tags]
  (->AddTags item-id tags))

(defn remove-tag [item-id tags]
  (->RemoveTags item-id tags))

(defn delete-item [item-id]
  (->DeleteItem item-id))

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
                                   :last-purchased String
                                   :list-id ListID})})

    (r/declare-pstate s $$tags {String (r/set-schema ItemID {:subindex? true})})
    ;; TODO: Sanity check inputs
    (r/<<sources s
                 (r/source> *shopping-list-depot :> *list)
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
                                (r/local-transform> [(rp/keypath *list-id :subscribers) (rp/set-elem *subscriber) r/NONE>] $$shopping-lists))

                 (r/source> *item-depot :> *item)
                 (r/<<subsource *item
                                (r/case> Item)
                                (identity *item :> {:keys [*item-id *list-id *description *notes *quantity]})
                                (r/|hash *list-id)
                                (r/local-select> [(rp/keypath *list-id)] $$shopping-lists :> *list)
                                (r/<<if *list
                                        (r/|hash *item-id)
                                        (r/local-transform> [(rp/keypath *item-id)
                                                             (rp/termval {:list-id *list-id
                                                                          :item-id *item-id
                                                                          :description *description
                                                                          :notes *notes
                                                                          :quantity *quantity
                                                                          :tags #{}})] $$items)
                                        (r/ack-return> *item-id))

                                (r/case> ItemEdits)
                                (identity *item :> {:keys [*item-id *edits]})
                                (r/local-select> [(rp/keypath *item-id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *edits :> {:keys [*field *value]})
                                        (r/local-transform> [(rp/keypath *item-id *field)
                                                             (rp/termval *value)] $$items)
                                        (r/ack-return> :success))

                                (r/case> AddTags)
                                (identity *item :> {:keys [*item-id *tags]})
                                (r/local-select> [(rp/keypath *item-id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             rp/NONE-ELEM
                                                             (rp/termval *item-id)] $$tags)
                                        (r/|hash *item-id)
                                        (r/local-transform> [(rp/keypath *item-id :tags)
                                                             rp/NONE-ELEM
                                                             (rp/termval *tag)] $$items)
                                        (r/ack-return> :success))

                                (r/case> RemoveTags)
                                (identity *item :> {:keys [*item-id *tags]})
                                (r/local-select> [(rp/keypath *item-id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             (rp/set-elem *item-id)
                                                             r/NONE>] $$tags)
                                        (r/|hash *item-id)
                                        (r/local-transform> [(rp/keypath *item-id :tags)
                                                             (rp/set-elem *tag)
                                                             r/NONE>] $$items)
                                        (r/ack-return> :success))

                                (r/case> PurchasedOn)
                                (identity *item :> {:keys [*item-id *date]})
                                (r/local-select> [(rp/keypath *item-id)] $$items :> *item)
                                (r/<<if *item
                                        (r/local-transform> [(rp/keypath *item-id :last-purchased)
                                                             (rp/termval *date)] $$items)
                                        (r/ack-return> :success))

                                (r/case> DeleteItem)
                                (identity *item :> {:keys [*item-id]})
                                (r/local-select> [(rp/keypath *item-id)] $$items :> *item)
                                (r/<<if *item
                                        (identity *item :> {:keys [*tags]})
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             (rp/set-elem *item-id)
                                                             r/NONE>] $$tags)
                                        (r/|hash *item-id)
                                        (r/local-transform> [(rp/keypath *item-id) r/NONE>] $$items))))))
