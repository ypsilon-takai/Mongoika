(ns mongoika.proper-mongo-collection
  (use [mongoika
        [conversion :only [MongoObject
                           str<-
                           keyword<-
                           <-mongo-object
                           <-db-object
                           mongo-object<-]]
        [params :only [fix-param]]])
  (require [mongoika
            [query :as query]])
  (import [clojure.lang IPersistentMap Sequential Named]
          [java.util List Iterator Map]
          [com.mongodb DBCollection DBObject DBCursor WriteResult MapReduceCommand MapReduceCommand$OutputType]
          [com.mongodb.gridfs GridFS GridFSInputFile GridFSDBFile]))

(defprotocol ProperMongoCollection
  (make-mongo-object-seq [this ^IPersistentMap params])
  (count-restricted-docs [this ^IPersistentMap restriction])
  (call-find-one [this ^IPersistentMap params])
  (call-delete [this ^IPersistentMap params]))

(defn- make-seq [proper-mongo-collection {:keys [limit map-after] :as params}]
  (if (and limit (= 0 (fix-param :limit limit)))
    []
    (let [cursor-seq (map <-mongo-object (make-mongo-object-seq proper-mongo-collection params))]
      (if map-after
        (map map-after cursor-seq)
        cursor-seq))))

(defn- count-docs [proper-mongo-collection {:keys [skip limit restrict] :as params}]
  (let [count (count-restricted-docs proper-mongo-collection restrict)
        count (if skip (- count skip) count)
        count (if (neg? count) 0 count)]
    (if limit
      (min limit count)
      count)))

(defn- fetch-one [proper-mongo-collection {:keys [order skip map-after] :as params}]
  (if (or order (and skip (not (= 0 skip))))
    (first (make-seq proper-mongo-collection (assoc params :limit 1)))
    (let [object (<-mongo-object (call-find-one proper-mongo-collection params))]
      (if (and map-after object)
        (map-after object)
        object))))

(defn- delete! [proper-mongo-collection {:keys [restrict skip limit] :as params}]
  (when (or skip limit)
    (throw (UnsupportedOperationException. "Deletion with limit or skip is unsupported.")))
  (call-delete proper-mongo-collection params)
  nil)

(defn- apply-db-cursor-params! [cursor params]
  (doseq [[param apply-fn] {:order #(.sort ^DBCursor cursor ^DBObject %)
                            :skip #(.skip ^DBCursor cursor ^int %)
                            :limit #(.limit ^DBCursor cursor ^int %)
                            :batch-size #(.batchSize ^DBCursor cursor ^int %)
                            :query-options #(.setOptions ^DBCursor cursor ^int %)}]
    (if (contains? params param)
      (apply-fn (fix-param param (param params)))))
  cursor)

(defn- update-in-db-collection [db-collection {:keys [restrict project order skip map-after] :as params} operations upsert?]
  (when skip
    (throw (UnsupportedOperationException. "Update with limit or skip is unsupported.")))
  (let [updated-object (<-mongo-object (.findAndModify ^DBCollection db-collection
                                                       ^DBObject (fix-param :restrict restrict)
                                                       ^DBObject (fix-param :project project)
                                                       ^DBObject (fix-param :order order)
                                                       false ; remove
                                                       ^DBObject (mongo-object<- operations)
                                                       true ; returnNew
                                                       upsert?))]
    (if (and map-after updated-object)
      (map-after updated-object)
      updated-object)))

(def ^{:private true} map-reduce-command-output-type {:inline MapReduceCommand$OutputType/INLINE
                                                      :merge MapReduceCommand$OutputType/MERGE
                                                      :reduce MapReduceCommand$OutputType/REDUCE
                                                      :replace MapReduceCommand$OutputType/REPLACE})

(extend-type DBCollection
  ProperMongoCollection
  (make-mongo-object-seq [this ^IPersistentMap {:keys [restrict project] :as params}]
    (iterator-seq (apply-db-cursor-params! (.find ^DBCollection this
                                                  ^DBObject (fix-param :restrict restrict)
                                                  ^DBObject (fix-param :project project))
                                           params)))
  (count-restricted-docs [this ^IPersistentMap restriction]
    (.count ^DBCollection this
            ^DBObject (fix-param :restrict restriction)))
  (call-find-one [this ^IPersistentMap {:keys [restrict project]}]
    (.findOne ^DBCollection this
              ^DBObject (fix-param :restrict restrict)
              ^DBObject (fix-param :project project)))
  (call-delete [this ^IPersistent {:keys [restrict]}]
    (.remove ^DBCollection this
             ^DBObject (fix-param :restrict restrict)))
  query/QuerySource
  (collection-name [this]
    (.getName this))
  (make-seq [this ^IPersistentMap params]
    (make-seq this params))
  (count-docs [this ^IPersistentMap params]
    (count-docs this params))
  (fetch-one [this ^IPersistentMap params]
    (fetch-one this params))
  (insert! [this ^IPersistentMap params ^IPersistentMap doc]
    (first (query/insert-multi! this params [doc])))
  (insert-multi! [this ^IPersistentMap {:keys [map-after]} ^Sequential docs]
    (let [mongo-objects (map mongo-object<- docs)]
      (.insert ^DBCollection this
               ^List mongo-objects)
      (let [inserted-objects (map <-mongo-object mongo-objects)]
        (if map-after
          (map map-after inserted-objects)
          inserted-objects))))
  (update! [this ^IPersistentMap params ^IPersistentMap operations]
    (update-in-db-collection this params operations false))
  (update-multi! [this ^IPersistentMap {:keys [restrict skip limit] :as params} ^IPersistentMap operations]
    (when skip
      (throw (UnsupportedOperationException. "Update with skip is unsupported.")))
    (if limit
      (if (not (= 1 (fix-param :limit limit)))
        (throw (UnsupportedOperationException. "Update with limit is supported only with 1."))
        (if (query/update! this params operations) 1 0))
      (.getN ^WriteResult (.update ^DBCollection this
                                   ^DBObject (fix-param :restrict restrict)
                                   ^DBObject (mongo-object<- operations)
                                   false ; upsert
                                   true)))) ; multi
  (upsert! [this ^IPersistentMap params ^IPersistentMap operations]
    (update-in-db-collection this params operations true))
  (delete-one! [this ^IPersistentMap {:keys [restrict project order skip map-after] :as params}]
    (when skip (throw (UnsupportedOperationException. "Deletion with skip is unsupported.")))
    (let [deleted-object (<-mongo-object (.findAndModify ^DBCollection this
                                                         ^DBObject (fix-param :restrict restrict)
                                                         ^DBObject (fix-param :project project)
                                                         ^DBObject (fix-param :order order)
                                                         true ; remove
                                                         nil ; update
                                                         false ; returnNew
                                                         false))] ;upsert
      (if map-after
        (map-after deleted-object)
        deleted-object)))
  (delete! [this ^IPersistentMap params]
    (delete! this params))
  (map-reduce! [this ^IPersistentMap {:keys [limit order restrict skip map-after] :as params} ^IPersistentMap {:keys [map reduce finalize out out-type scope verbose] :as options}]
    (when skip (throw (UnsupportedOperationException. "Map/Reduce with skip is unsupported.")))
    (when map-after (throw (UnsupportedOperationException. "Map/Reduce with map-after is unsupported.")))
    (<-mongo-object (.mapReduce this
                                ^MapReduceCommand (let [command (MapReduceCommand. ^DBCollection this
                                                                                   ^String map
                                                                                   ^String reduce
                                                                                   ^String (when out (query/collection-name out))
                                                                                   ^MapReduceCommand$OutputType (or (map-reduce-command-output-type out-type) out-type MapReduceCommand$OutputType/REPLACE)
                                                                                   ^DBObject (fix-param :restrict restrict))]
                                                    (when limit (.setLimit command ^int (fix-param :limit limit)))
                                                    (when order (.setSort command ^DBObject (fix-param :order order)))
                                                    (when finalize (.setFinalize command ^String finalize))
                                                    (when scope (.setScope command ^Map (clojure.core/reduce (fn [variables [key val]]
                                                                                                               (assoc variables ((if (instance? Named key) name str) key) (mongo-object<- val)))
                                                                                                             {}
                                                                                                             scope)))
                                                    (when-not (nil? verbose) (.setVerbose command ^Boolean (boolean verbose)))
                                                    command)))))

(extend-protocol MongoObject
  GridFSDBFile
  (<-mongo-object [this]
    (assoc (<-db-object this)
      :data (.getInputStream ^GridFSDBFile this)))
  GridFSInputFile
  (<-mongo-object [this]
    (<-db-object this)))

(gen-interface :name mongoika.proper-mongo-collection.GridFSDBFileSettable
               :methods [[_setFrom [com.mongodb.gridfs.GridFS com.mongodb.gridfs.GridFSDBFile] com.mongodb.gridfs.GridFSDBFile]])

(extend-type GridFS
  ProperMongoCollection
  (make-mongo-object-seq [this ^IPersistentMap {:keys [restrict] :as params}]
    (map (fn [file]
           ;; GridFS#getFileList does not set a GridFS in fetched files.
           ;; A GridFS mus be set in a GridFSDBFile when read data from it.
           (._setFrom (proxy [GridFSDBFile mongoika.proper-mongo-collection.GridFSDBFileSettable] []
                        (_setFrom [^GridFS grid-fs ^GridFSDBFile file]
                          ;; GridFSDBFile does not support putAll().
                          (doseq [key (.keySet ^DBObject file)]
                            (.put this
                                  ^String key
                                  ^Object (.get ^DBObject file ^String key)))
                          (.setGridFS this ^GridFS grid-fs)
                          this))
                      this
                      file))
         (iterator-seq (apply-db-cursor-params! (.getFileList ^GridFS this
                                                              ^DBObject (fix-param :restrict restrict))
                                                params))))
  (count-restricted-docs [this ^IPersistentMap restriction]
    (.count (.getFileList ^GridFS this
                          ^DBObject (fix-param :restrict restriction))))
  (call-find-one [this ^IPersistentMap {:keys [restrict]}]
    (.findOne ^GridFS this
              ^DBObject (fix-param :restrict restrict)))
  (call-delete [this ^IPersistentMap {:keys [restrict]}]
    (.remove ^GridFS this
             ^DBObject (fix-param :restrict restrict)))
  query/QuerySource
  (collection-name [this]
    (.getBucketName this))
  (make-seq [this ^IPersistentMap params]
    (make-seq this params))
  (count-docs [this ^IPersistentMap params]
    (count-docs this params))
  (fetch-one [this ^IPersistentMap params]
    (fetch-one this params))
  (insert! [this ^IPersistentMap {:keys [map-after]} ^IPersistentMap {:keys [data] :as doc}]
    (let [file (.createFile ^GridFS this data)]
      (doseq [[attribute value] (dissoc doc :data)]
        (.put ^GridFSInputFile file ^String (str<- attribute) ^Object (mongo-object<- value)))
      ;; Avoid a bug: An invalid length is set if the chunk size is less than or equal to the data length.
      ;; The save method set the chunk size before saving if it received a chunk size.
      (let [chunk-size (.getChunkSize file)]
        (.setChunkSize file GridFS/DEFAULT_CHUNKSIZE)
        (.save file chunk-size))
      (let [inserted-file (assoc (<-mongo-object file)
                            :data data)]
        (if map-after
          (map-after inserted-file)
          inserted-file))))
  (insert-multi! [this ^IPersistentMap params ^Sequential docs]
    (doall (map #(query/insert! this params %) docs)))
  (update! [this ^IPersistentMap params ^IPersistentMap operations]
    (throw (UnsupportedOperationException. "GridFS does not support update!.")))
  (update-multi! [this ^IPersistentMap params ^IPersistentMap operations]
    (throw (UnsupportedOperationException. "GridFS does not support update!.")))
  (upsert! [this ^IPersistentMap params ^IPersistentMap operations]
    (throw (UnsupportedOperationException. "GridFS does not support upsert!.")))
  (delete-one! [this ^IPersistentMap params]
    (throw (UnsupportedOperationException. "GridFS does not support delete-one!.")))
  (delete! [this ^IPersistentMap params]
    (delete! this params))
  (map-reduce [this ^IPersistentMap params ^IPersistentMap options]
    (throw (UnsupportedOperationException. "GridFS does not support map-reduce!."))))
