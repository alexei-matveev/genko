(ns genko.kuzu
  (:require
   [clojure.tools.logging :as log])
  (:import [com.kuzudb Database Connection
            QueryResult FlatTuple Value
            PreparedStatement DataType DataTypeID]))


;; NOTE: Kuzu *overwrites* contents of the FlatTuple on iterations
;; with .getNext [1]! If you force the result seq first and only look
;; inside FlatTuple *after* that you will be dissappointed:
;;
;;   (defn- result-seq [^QueryResult result]
;;     (lazy-seq
;;      (when (.hasNext result)
;;        (cons (.getNext result)
;;              (result-seq result)))))
;;
;;   (map str (doall (result-seq result))) ; WRONG!
;;   =>
;;   ("Zhang|2022|Noura\n"
;;    "Zhang|2022|Noura\n"
;;    "Zhang|2022|Noura\n"
;;    "Zhang|2022|Noura\n")
;;
;; This would work:
;;
;;   (mapv str (result-seq result))
;;   =>
;;   ["Adam|2020|Karissa\n"
;;    "Adam|2020|Zhang\n"
;;    "Karissa|2021|Zhang\n"
;;    "Zhang|2022|Noura\n"]
;;
;; But why letting such a leaky abstraction propagate further?
;;
;; [1] https://docs.kuzudb.com/client-apis/java/

(defn- get-value
  "Inline it!"
  [^Value value]
  ;; As long as we dont invoke instance methods of `x`, we need
  ;; neither reflection nor type hints. The method `.getValue` is
  ;; generic and effectivly always returns an `Object` after type
  ;; erasure:
  (let [x (.getValue value)]
    ;; ^DataType data-type (.getDataType value)
    ;; ^DataTypeID type-id (.getID data-type)
    ;; [x (class x)]
    x))


(defn- as-maps [^QueryResult result]
  (let [n (.getNumColumns result)
        cols (for [i (range n)]
               (keyword (.getColumnName result i)))]
    (lazy-seq
     (when (.hasNext result)
       (cons (let [^FlatTuple tuple (.getNext result)
                   values (for [i (range n)
                                :let [^Value value (.getValue tuple i)]]
                            (get-value value))]
               (zipmap cols values))
             (as-maps result))))))


(defn query
  "Query database and return results as a lazy Clojure sequence. Note
  that you need to eventually force evaluation of the sequence before
  closing connection to the database!"
  [^Connection conn ^String q]
  (let [^QueryResult result (.query conn q)]
    (as-maps result)))


(defn execute
  "Execute query with arguments and return results as a lazy Clojure
  sequence. The caller should evaluate results before closing
  connection!"
  [^Connection conn ^String q kws]

  ;; TODO: Specialize code for (isinstance? PreparedStatement q)!
  (let [^PreparedStatement p (.prepare conn q)
        ;; Keyword Arguments as Map<String,Value>. Should we also
        ;; allow String keys in the input?
        value-map (into {} (for [[k v] kws]
                             [(name k) (Value. v)]))
        results (.execute conn p value-map)]
    (as-maps results)))


(defn- demo []
  ;; Create an empty in-memory or on-disk database and connect to it.
  (with-open [^Database db (Database. ":memory:")
              ^Connection conn (Connection. db)]

    ;; Create tables, Load data.
    (doseq [q ["CREATE NODE TABLE User(name STRING PRIMARY KEY, age INT64)"
               "CREATE NODE TABLE City(name STRING PRIMARY KEY, population INT64)"
               "CREATE REL TABLE Follows(FROM User TO User, since INT64)"
               "CREATE REL TABLE LivesIn(FROM User TO City)"
               "COPY User FROM 'resources/user.csv'"
               "COPY City FROM 'resources/city.csv'"
               "COPY Follows FROM 'resources/follows.csv'"
               "COPY LivesIn FROM 'resources/lives-in.csv'"]]
      ;; All of these are queries as well, and return a 1x1 table,
      ;; with a string as `:result`, cf.:
      ;;
      ;;   (println (as-maps (.query conn q)))
      ;;   =>
      ;;   ({:result Table User has been created.})
      ;;   ({:result Table City has been created.})
      ;;   ({:result Table Follows has been created.})
      ;;   ({:result Table LivesIn has been created.})
      ;;   ({:result 4 tuples have been copied to the User table.})
      ;;   ({:result 3 tuples have been copied to the City table.})
      ;;   ({:result 4 tuples have been copied to the Follows table.})
      ;;   ({:result 4 tuples have been copied to the LivesIn table.})
      (.query conn q))

    ;; Execute a simple query.  We must force the lazy seq before
    ;; closing `Connection` & `Database`, see `with-open` above!
    ;; Otherwise return values cannot be printed outside of this
    ;; function: "Error printing return value at
    ;; com.kuzudb.Native/kuzuQueryResultToString ...". This commented
    ;; code would work here though:
    ;;
    ;; (while (.hasNext result)
    ;;     (let [^FlatTuple row (.getNext result)]
    ;;       (println row)))
    (let [q "MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;"]
      (doall (query conn q)))))


(comment
  (demo)
  =>
  ({:a.name "Adam", :f.since 2020, :b.name "Karissa"}
   {:a.name "Adam", :f.since 2020, :b.name "Zhang"}
   {:a.name "Karissa", :f.since 2021, :b.name "Zhang"}
   {:a.name "Zhang", :f.since 2022, :b.name "Noura"})

  ;; // Create an empty on-disk database and connect to it
  ;; Database db = new Database("example.kuzu");
  ;; Connection conn = new Connection(db);
  (def db (Database. ":memory:"))
  db
  (.close db)
  (def conn (Connection. db))
  conn
  (.close conn)

  ;; // Create tables.
  ;; conn.query("CREATE NODE TABLE User(name STRING PRIMARY KEY, age INT64)");
  ;; conn.query("CREATE NODE TABLE City(name STRING PRIMARY KEY, population INT64)");
  ;; conn.query("CREATE REL TABLE Follows(FROM User TO User, since INT64)");
  ;; conn.query("CREATE REL TABLE LivesIn(FROM User TO City)");
  (do
    (.query conn "CREATE NODE TABLE User(name STRING PRIMARY KEY, age INT64)")
    (.query conn "CREATE NODE TABLE City(name STRING PRIMARY KEY, population INT64)")
    (.query conn "CREATE REL TABLE Follows(FROM User TO User, since INT64)")
    (.query conn "CREATE REL TABLE LivesIn(FROM User TO City)"))

  (= DataTypeID/STRING DataTypeID/STRING) => true
  DataTypeID/STRING ;; => #object[com.kuzudb.DataTypeID 0x7d7f248d "STRING"]
  (.value DataTypeID/STRING) => 50
  (.value DataTypeID/INT64) => 23

  ;; // Load data.
  ;; conn.query("COPY User FROM 'src/main/resources/user.csv'");
  ;; conn.query("COPY City FROM 'src/main/resources/city.csv'");
  ;; conn.query("COPY Follows FROM 'src/main/resources/follows.csv'");
  ;; conn.query("COPY LivesIn FROM 'src/main/resources/lives-in.csv'");
  (do
    (.query conn "COPY User FROM 'resources/user.csv'")
    (.query conn "COPY City FROM 'resources/city.csv'")
    (.query conn "COPY Follows FROM 'resources/follows.csv'")
    (.query conn "COPY LivesIn FROM 'resources/lives-in.csv'"))

  ;; // Execute a simple query.
  ;; QueryResult result =
  ;;         conn.query("MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;");
  (query conn
         "MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;")

  (let [q "match (a:User)-[f:Follows]->(b:User) where f.since < $year
           return a.name, f.since, b.name;"]
    (execute conn q {:year 2021}))

  ;; https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/DataTypeID.java

  ;; Close and release the underlying resources. This method is
  ;; invoked automatically on objects managed by the
  ;; try-with-resources statement.
  (.close conn)
  (.close db)
  nil)
