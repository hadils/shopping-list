(ns shopping-list.module
  (:require
   [com.rpl.rama :as r]
   [com.rpl.rama.path :as rp]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops])
  (:import
   (java.util UUID)))

(def ListID String)
(def ItemID String)

(defrecord ShoppingList [id name author])
(defrecord ShoppingListEdit [field value])
(defrecord ShoppingListEdits [id edits])
(defrecord AddSubscriber [id subscriber])
(defrecord RemoveSubscriber [id subscriber])
(defrecord DeleteShoppingList [id])

(defn create-shopping-list
  ([id]
   (create-shopping-list id nil nil))
  ([id name author]
   (->ShoppingList id name author)))

(defn name-edit [value]
  (->ShoppingListEdit :name value))

(defn author-edit [value]
  (->ShoppingListEdit :author value))

(defn add-subscriber [id subscriber]
  (->AddSubscriber id subscriber))

(defn remove-subecriber [id subscriber]
  (->RemoveSubscriber id subscriber))

(defrecord Item [id list-id description notes quantity])
(defrecord ItemEdit [field value])
(defrecord ItemEdits [id edits])
(defrecord PurchasedOn [id date])
(defrecord AddTags [id tags])
(defrecord RemoveTags [id tags])
(defrecord DeleteItem [id])

(defn create-item
  ([id list-id]
   (create-item id list-id nil nil nil))
  ([id list-id description notes quantity]
   (->Item id list-id description notes quantity)))

(defn description-edit [value]
  (->ItemEdit :description value))

(defn notes-edit [value]
  (->ItemEdit :notes value))

(defn quantity-edit [value]
  (->ItemEdit :quantity value))

(defn add-tag [id tags]
  (->AddTags id tags))

(defn remove-tag [id tags]
  (->RemoveTags id tags))

(defn delete-item [id]
  (->DeleteItem id))

(r/defmodule Module [setup topologies]
  (r/declare-depot setup *shopping-list-depot (r/hash-by :id))
  (r/declare-depot setup *item-depot (r/hash-by :id))

  (let [s (r/stream-topology topologies "shopping-list")]
    (r/declare-pstate s $$shopping-lists {ListID
                                          (r/fixed-keys-schema
                                           {:id ListID
                                            :name String
                                            :author String
                                            :subscribers (r/set-schema String)
                                            :items (r/vector-schema ItemID)})})

    (r/declare-pstate s $$items {ItemID
                                 (r/fixed-keys-schema
                                  {:id ItemID
                                   :description String
                                   :notes String
                                   :quantity Long
                                   :tags (r/set-schema String)
                                   :last-purchased String
                                   :list-id ListID})})

    (r/declare-pstate s $$tags {String (r/set-schema ItemID {:subindex? true})})
    (r/<<sources s
                 (r/source> *shopping-list-depot :> *list)
                 (r/<<subsource *list
                                (r/case> ShoppingList)
                                (identity *list :> {:keys [*id *name *author]})
                                (r/local-transform> [(rp/keypath *id)
                                                     (rp/termval {:id *id
                                                                  :name *name
                                                                  :author *author
                                                                  :subscribers #{}
                                                                  :items []})] $$shopping-lists)
                                (r/ack-return> *id)

                                (r/case> DeleteShoppingList)
                                (identity *list :> {:keys [*id]})
                                (r/local-transform> [(rp/keypath *id)
                                                     r/NONE>] $$shopping-lists)

                                (r/case> ShoppingListEdits)
                                (identity *list :> {:keys [*id *edits]})
                                (ops/explode *edits :> {:keys [*field *value]})
                                (r/local-transform> [(rp/keypath *id *field) (rp/termval *value)] $$shopping-lists)

                                (r/case> AddSubscriber)
                                (identity *list :> {:keys [*id *subscriber]})
                                (r/local-transform> [(rp/keypath *id :subscribers) rp/NONE-ELEM (rp/termval *subscriber)] $$shopping-lists)

                                (r/case> RemoveSubscriber)
                                (identity *list :> {:keys [*id *subscriber]})
                                (r/local-transform> [(rp/keypath *id :subscribers) (rp/set-elem *subscriber) r/NONE>] $$shopping-lists))

                 (r/source> *item-depot :> *item)
                 (r/<<subsource *item
                                (r/case> Item)
                                (identity *item :> {:keys [*id *list-id *description *notes *quantity]})
                                (r/|hash *list-id)
                                (r/local-select> [(rp/keypath *list-id)] $$shopping-lists :> *list)
                                (r/<<if *list
                                        (r/local-transform> [(rp/keypath *list-id) :items rp/AFTER-ELEM
                                                             (rp/termval *id)] $$shopping-lists)
                                        (r/|hash *id)
                                        (r/local-transform> [(rp/keypath *id)
                                                             (rp/termval {:list-id *list-id
                                                                          :id *id
                                                                          :description *description
                                                                          :notes *notes
                                                                          :quantity *quantity
                                                                          :tags #{}})] $$items)
                                        (r/ack-return> *id))

                                (r/case> ItemEdits)
                                (identity *item :> {:keys [*id *edits]})
                                (r/local-select> [(rp/keypath *id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *edits :> {:keys [*field *value]})
                                        (r/local-transform> [(rp/keypath *id *field)
                                                             (rp/termval *value)] $$items)
                                        (r/ack-return> :success))

                                (r/case> AddTags)
                                (identity *item :> {:keys [*id *tags]})
                                (r/local-select> [(rp/keypath *id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             rp/NONE-ELEM
                                                             (rp/termval *id)] $$tags)
                                        (r/|hash *id)
                                        (r/local-transform> [(rp/keypath *id :tags)
                                                             rp/NONE-ELEM
                                                             (rp/termval *tag)] $$items)
                                        (r/ack-return> :success))

                                (r/case> RemoveTags)
                                (identity *item :> {:keys [*id *tags]})
                                (r/local-select> [(rp/keypath *id)] $$items :> *item)
                                (r/<<if *item
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             (rp/set-elem *id)
                                                             r/NONE>] $$tags)
                                        (r/|hash *id)
                                        (r/local-transform> [(rp/keypath *id :tags)
                                                             (rp/set-elem *tag)
                                                             r/NONE>] $$items)
                                        (r/ack-return> :success))

                                (r/case> PurchasedOn)
                                (identity *item :> {:keys [*id *date]})
                                (r/local-select> [(rp/keypath *id)] $$items :> *item)
                                (r/<<if *item
                                        (r/local-transform> [(rp/keypath *id :last-purchased)
                                                             (rp/termval *date)] $$items)
                                        (r/ack-return> :success))

                                (r/case> DeleteItem)
                                (identity *item :> {:keys [*id]})
                                (r/local-select> [(rp/keypath *id)] $$items :> *item)
                                (r/<<if *item
                                        (identity *item :> {:keys [*tags]})
                                        (ops/explode *tags :> *tag)
                                        (r/|hash *tag)
                                        (r/local-transform> [(rp/keypath *tag)
                                                             (rp/set-elem *id)
                                                             r/NONE>] $$tags)
                                        (r/|hash *id)
                                        (r/local-transform> [(rp/keypath *id) r/NONE>] $$items))))))
