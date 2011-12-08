![][LogoImage]

# Mongoika

A MongoDB Library for Clojure.

Mongoika simplify building queries behaved like lazy sequences, and supports basic operations and GridFS using Mongo Java Driver.

## Examples

```clojure
;; Use mongoika namespace.
(use 'mongoika)
    
;; Connect to a MongoDB server.
(with-mongo [connection {:host your-mongodb-host :port your-mongodb-port}]
  ;; Bind a database dynamically.
  (with-db-binding (database connection :your-database)
    ;; Insertion:
    (insert! :fruits {:name "Banana" :color :yellow :price 100})
    (insert! :fruits {:name "Apple" :color :red :price 80})

    ;; Multiple insertion:
    (insert-multi! :fruits
                   {:name "Lemon" :color :yellow :price 50}
                   {:name "Strawberry" :color :red :price 200})

    ;; Fetch all:
    (query :fruits)
    ; => [banana apple lemon strawberry]

    ;; Find:
    (restrict :color :red :fruits)
    ; => [apple strawberry]
    
    (restrict :price {> 100} :fruits)
    ; => [banana strawberry]

    ;; Sort:
    (order :price :asc :fruits)
    ; => [lemon apple banana strawberry]

    ;; Find and sort:
    (order :price :desc (restrict :color :yellow :fruits))
    ; => [banana lemon]

    ;; Fetch first:
    (fetch-one (order :price :desc (restrict :color :yellow :fruits)))))
    ; => banana
```
## Usage
### Connect to a MongoDB server.

```clojure
(mongo {:host "127.0.0.1" :port 27017})
```

```clojure
; Connect with options
(mongo {:host "127.0.0.1" :port 27017} {:safe true :socketTimeout 5})
```

`mongo` returns a Mongo instance of Mongo Java Driver. It has connection pooling.

```clojure
(with-mongo [connection {:host "127.0.0.1" :port 27017} {:safe true}]
  ...)
```

`with-mongo` binds a Mongo instance. The mongo instance is closed automatically.

### Use a database

```clojure
(database connection :your-database)
```

`database` returns a DB instance.

```clojure
(with-db-binding (database connection :your-database)
  ...)
```

`with-db-binding` binds a specified database to the `*db*` global var dynamically, and most functions in Mongoika use it. This macro calls `.requestStart` before the body is executed, and `.requestDone` after.

### Insertion

```clojure
(insert! :foods {:name "Cheese" :quantity 120 :price 300})
; => db.foods.insert({name: "Cheese", quantity: 120, price 300})
```

`insert!` inserts a document to the specified collection, and returns the inserted document as a map that has an `_id` field. Each key in a map returned from `insert!` is converted to a keyword.

```clojure
(insert-multi! :foods
               {:name "Cookie" :quantity 70 :price 120}
               {:name "Banana" :quantity 40 :price 100}
               {:name "Chunky Bacon" :quantity 600 :price 800})
```

`insert-multi!` inserts multiple documents, and returns inserted documents.

### Fetching

```clojure
(query :foods)
; => db.foods.find()
```

`query` makes a query behaved like a lazy sequence that contains all documents in the specified collection.

```clojure
(doseq [food (query :foods)]
  (println (:name food)))
```

This code prints names of all foods.

#### Restriction

```clojure
(restrict :name "Cheese" :foods)
; => db.foods.find({name: "Cheese"})

(restrict :quantity {:$gt 100} :price {:$lt 300} :foods)
; => db.foods.find({quantity: {$gt: 100}, price: {$lt: 300}})
```

You can use following functions as operators in conditions.

    > => < <= mod type not

```clojure
(restrict :quantity {> 100} :price {< 300} :foods)
; => db.foods.find({quantity: {$gt: 100}, price: {$lt: 300}})
```

#### Projection

```clojure
(project :name :price :foods)
; => db.foods.find({}, {name: 1, price: 1})

(project :name :price (restrict :price {> 100} :foods))
; => db.foods.find({price: {$gt: 100}}, {name: 1, price: 1})
```

#### Sort

```clojure
(order :price :asc :foods)
; => db.foods.find().sort({price: 1})

(order :price :desc (restrict :quantity {< 100} :foods))
; => db.foods.find({quantity: {$lt: 100}}).sort({price: -1})
```

#### Limit

```clojure
(limit 3 :foods)
; => db.foods.find().limit(3)

(limit 2 (order :price :asc (restrict :price {> 50} :foods)))
; => db.foods.find({price: {$gt: 50}}).sort({price: 1}).limit(2)
```

#### Skip

```clojure
(skip 2 :foods)
; => db.foods.find().skip(2)

(skip 3 (order :price :asc :foods))
; => db.foods.find().sort({price: 1}).skip(3)
```

These functions receive a keyword or a query and return a new query.

#### Counting

```clojure
(count (restrict :price {> 100} :foods))
```

MongoDB does not return any documents when `count` is called.

### Update

```clojure
(update! :$set {:quantity 80} (restrict :name "Banana" :foods))
; => db.foods.find({name: "Banana"}, {$set: {quantity: 80}}, false, false)
```

`update!` updates just one document that are returned from a received query.

```clojure
(upsert! :$set {:price 100 :quantity 80} (restrict :name "Cheese" :foods))
; => db.foods.update({name: "Cheese"}, {$set: {price: 100, autntity: 80}}, true, false)
```

`upsert!` does an "upsert" operation.

```clojure
(update-multi! :$inc {:price 10} :foods)
; => db.foods.update({}, {$inc: {price: 10}}, false, true)
```

`update-multi!` updates all documents that are returned from a received query.

### Deletion

```clojure
(delete! (restrict :price {< 100} :foods))
; => db.foods.remove({price: {$lt: 100}})
```

`delete!` removes all documents that are returned from a received query.

### GridFS

```clojure
(grid-fs :images)
```

`grid-fs` returns a GridFS instance. You can pass it to functions as a query.

```clojure
(query (grid-fs :images))
```

```clojure
(restrict :_id image-id (grid-fs :images))
```

```clojure
(count (restrict :metadata.width {> 200} (grid-fs :images)))
```

A map returned from a GridFS query has a `:data` field, and it's value a sequence of bytes.

You can use `insert!`, `insert-multi!` and `delete!` for GridFS, but `update!`, `upsert!` and `update-multi!` does not support GridFS.

```clojure
(insert! {:data byte-array-or-iterator
          :filename "image1.png"
          :contentType "image/png"}
         (grid-fs :images))
```

```clojure
(delete! (restrict :_id image-id (grid-fs :images)))
```

## Install

Add

```clojure
[mongoika "0.6"]
```
to your project.clj.

## Mongoika?

Mongoika is named from a cuttlefish called "Mongou Ika" (紋甲イカ) in Japanese.

[LogoImage]: https://raw.github.com/yuushimizu/Mongoika/master/logo.png
