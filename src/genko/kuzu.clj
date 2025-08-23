(ns genko.kuzu
  (:require
   [clojure.tools.logging :as log])
  (:import [com.kuzudb Database Connection
            QueryResult FlatTuple Value
            DataType DataTypeID]))


(defn- result-seq [^QueryResult result]
  (lazy-seq
   (when (.hasNext result)
     (cons (.getNext result)
           (result-seq result)))))


(defn- get-value [^Value value]
  ;; ^DataType data-type (.getDataType value)
  ;; ^DataTypeID type-id (.getID data-type)
  (let [x (.getValue value)]
    #_[x (class x)]
    x))


(defn- tuples [^QueryResult result]
  (let [n (.getNumColumns result)]
    (lazy-seq
     (when (.hasNext result)
       (cons (let [^FlatTuple tuple (.getNext result)]
               (for [i (range n)
                     :let [^Value value (.getValue tuple i)]]
                 (get-value value)))
             (tuples result))))))


(comment
  ;; We should use `with-open` but return values cannot be printed
  ;; after closing DB! "Error printing return value at
  ;; com.kuzudb.Native/kuzuQueryResultToString ..."
  ;;
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
  (let [
        ^QueryResult result (.query conn "MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;")
        ;; ^QueryResult result (.query conn "MATCH (a:User) RETURN a.name;")
        ]
    (println result)
    (println (.hasNext result))
    ;; while (result.hasNext()) {
    ;;     FlatTuple row = result.getNext();
    ;;     System.out.print(row);
    ;; }
    #_(while (.hasNext result)
        (let [^FlatTuple row (.getNext result)]
          (println row)))

    ;; NOTE: Kuzu *overwrites* contents of the FlatTuple on iterations
    ;; with .getNext [1]! If you force the result seq first and only
    ;; look inside FlatTuple *after* that you will be dissappointed:
    ;;
    ;;   (map str (doall (result-seq result))) ; WRONG!
    ;;   =>
    ;;   ("Zhang|2022|Noura\n" "Zhang|2022|Noura\n" "Zhang|2022|Noura\n" "Zhang|2022|Noura\n")
    ;;
    ;; This does work however:
    ;;
    ;;   (mapv str (result-seq result))
    ;;   =>
    ;;   ["Adam|2020|Karissa\n"
    ;;    "Adam|2020|Zhang\n"
    ;;    "Karissa|2021|Zhang\n"
    ;;    "Zhang|2022|Noura\n"]
    ;;
    ;; [1] https://docs.kuzudb.com/client-apis/java/

    (tuples result))
  =>
  (("Adam" 2020 "Zhang") ("Karissa" 2021 "Zhang") ("Zhang" 2022 "Noura") ("Zhang" 2022 "Noura"))

  ;; https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/DataTypeID.java

  ;; Close and release the underlying resources. This method is
  ;; invoked automatically on objects managed by the
  ;; try-with-resources statement.
  (.close conn)
  (.close db)
  nil)
