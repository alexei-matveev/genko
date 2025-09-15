(ns genko.kuzu
  (:import [com.kuzudb Database Connection
            QueryResult FlatTuple Value
            PreparedStatement
            DataType DataTypeID KuzuList KuzuStruct KuzuMap]))


;; Kuzu `Value` is a generic object, one needs to convert them to
;; `KuzuList`, `KuzuStruct` or `KuzuMap` to use type specific
;; methods. ChatGPT after 45s thinking with me researching for hours
;; to ask the right questions produce this recursive converter from
;; Kuzu Values to Clojure objects.
(defn- kuzu->clj
  [^Value v]
  (when (or (nil? v) (.isNull v))
    ;; null/NULL maps to nil
    nil)
  (let [^DataType dt (.getDataType v)
        id (.getID dt)]
    (cond
      ;; STRUCT -> Clojure map (keywordized keys)
      (= id DataTypeID/STRUCT)
      (let [^KuzuStruct ks (KuzuStruct. v)
            jmap (.toMap ks)] ;; returns java.util.Map<String, Value>
        (try
          (into {}
                (map (fn [^java.util.Map$Entry e]
                       [(keyword (.getKey e))
                        (kuzu->clj (.getValue e))])
                     (.entrySet jmap)))
          (finally
            (.close ks)))) ;; close wrapper (releases native refs)

      ;; LIST -> vector
      (= id DataTypeID/LIST)
      (let [^KuzuList kl (KuzuList. v)
            n (int (.getListSize kl))]
        (try
          (->> (range n)
               (mapv (fn [i] (kuzu->clj (.getListElement kl (long i))))))
          (finally
            (.close kl))))

      ;; MAP -> Clojure map (keys converted recursively, not
      ;; keywordized even if strings)
      (= id DataTypeID/MAP)
      (let [^KuzuMap km (KuzuMap. v)
            n (int (.getNumFields km))]
        (try
          (into {}
                (map (fn [i]
                       (let [k (kuzu->clj (.getKey km (long i)))
                             val (kuzu->clj (.getValue km (long i)))]
                         [k val]))
                     (range n)))
          (finally
            (.close km))))

      ;; fallback: primitive / simple Java object (String, Long, Double, Boolean, etc.)
      :else
      ;; .getValue returns the underlying Java value for primitive types
      (.getValue v))))


;; FIXME: Implement Clojure map -> Kuzu STRUCT! And/or Seq -> LIST!
(defn- clj->kuzu [v]
  (if (instance? Value v) v (Value. v)))


;; NOTE: Kuzu *overwrites* contents of the `FlatTuple` on iterations
;; with `.getNext` [1]! If you force the result seq *first* and only
;; look inside FlatTuple *after* that you will be dissappointed:
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


;; As long as we dont invoke instance methods on Clojure values, we
;; need neither reflection nor type hints. The method `.getValue` of
;; Kuzu `Value` is generic and effectivly always returns an `Object`
;; after type erasure. It is a blessing we dont need to go over all
;; the Kuzu types & type IDs yet [1]:
;;
;;   ^DataType data-type (.getDataType value)
;;   ^DataTypeID type-id (.getID data-type)
;;
;; FIXME: What if `QueryResult` `.hasNextQueryResult` [2]?  In the
;; current implementation of `as-maps` we would not even notice that!
;; Do we need another seq layer? And what about nested types?
;;
;; [1] https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/DataTypeID.java
;; [2] https://github.com/kuzudb/kuzu/commit/a39a10d6839458b865e29e5708bf1dff4207ad6d
(defn- as-maps [^QueryResult result]
  (let [n (.getNumColumns result)
        cols (for [i (range n)]
               (keyword (.getColumnName result i)))]
    (lazy-seq
     (when (.hasNext result)
       (let [^FlatTuple tuple (.getNext result)
             values (for [i (range n)
                          :let [^Value value (.getValue tuple i)]]
                      (try
                        (kuzu->clj value)
                        ;; Some Kuzu Values are opaque:
                        (catch Exception e value)))
             row (zipmap cols values)]
         (cons row (as-maps result)))))))


(defn query
  "Query database and return results as a lazy Clojure sequence. Note
  that you need to eventually force evaluation of the sequence before
  closing connection to the database!"
  [^Connection conn ^String q]
  (let [^QueryResult result (.query conn q)]
    (as-maps result)))


;; Should probably be a part of public interface:
(defn prepare
  "Prepare Cypher statement."
  [^Connection conn ^String q]
  (.prepare conn q))


(defn execute
  "Execute prepared Cypher statement with arguments and return results
  as a lazy Clojure sequence. The caller should evaluate results
  before closing connection!"
  [^Connection conn statement kws]
  (cond
    ;; Specialize for the case of a String instead of
    ;; PreparedStatement:
    (instance? String statement)
    (execute conn (prepare conn statement) kws)

    ;; Convert keyword arguments to a Map<String,Value>. Should we
    ;; also allow String keys in the input? Also pass (opaque?)
    ;; `Value` instances as is, do not wrap them.
    (instance? PreparedStatement statement)
    (let [value-map (into {} (for [[k v] kws]
                               [(name k) (clj->kuzu v)]))
          results (.execute conn statement value-map)]
      (as-maps results))))


(defn demo []
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
      ;; with a string as `:result`. These are the printed results of
      ;; the queries above:
      ;;
      ;;   ({:result Table User has been created.})
      ;;   ({:result Table City has been created.})
      ;;   ({:result Table Follows has been created.})
      ;;   ({:result Table LivesIn has been created.})
      ;;   ({:result 4 tuples have been copied to the User table.})
      ;;   ({:result 3 tuples have been copied to the City table.})
      ;;   ({:result 4 tuples have been copied to the Follows table.})
      ;;   ({:result 4 tuples have been copied to the LivesIn table.})
      (query conn q))

    ;; Execute a simple query.  We must force the lazy seq before
    ;; closing `Connection` & `Database`, see `with-open` above!
    ;; Otherwise return values cannot be printed outside of this
    ;; function.

    ;; FIXME: This possibly illegal syntax breaks Kuzu/JVM runtime
    ;; with SIGSEGV. Not an Exception or error message! Will we ever
    ;; need to pass nodes or relations back? See GitHub Issue [1]. We
    ;; leave it commented here.
    ;;
    ;; [1] https://github.com/kuzudb/kuzu/issues/5938
    #_(println
     (let [adams (query conn "match (a:User {name: 'Adam'}) return a")]
       (for [a adams]
         (do
           ;; Map with bindings seems ok at this point:
           ;;
           ;;   a= {:a #object[com.kuzudb.Value 0x218f2f51 {_ID: 0:0, ;; _LABEL: User, name: Adam, age: 30}]}
           (println "a=", a)
           (execute conn "match (a) where a = $a return a.name" a)))))

    (let [q "MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;"]
      (doall (query conn q)))))


(defn- inspect-value [^Value v]
  (let [^DataType dt (.getDataType v)
        ^DataTypeID tid (.getID dt)]
    (map str [tid v])))


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
  (do
    (def db (Database. ":memory:"))
    (def conn (Connection. db)))
  (do
    db
    conn
    (.close conn)
    (.close db))

  ;; // Create tables.
  ;; conn.query("CREATE NODE TABLE User(name STRING PRIMARY KEY, age INT64)");
  ;; conn.query("CREATE NODE TABLE City(name STRING PRIMARY KEY, population INT64)");
  ;; conn.query("CREATE REL TABLE Follows(FROM User TO User, since INT64)");
  ;; conn.query("CREATE REL TABLE LivesIn(FROM User TO City)");
  ;;
  ;; // Load data.
  ;; conn.query("COPY User FROM 'src/main/resources/user.csv'");
  ;; conn.query("COPY City FROM 'src/main/resources/city.csv'");
  ;; conn.query("COPY Follows FROM 'src/main/resources/follows.csv'");
  ;; conn.query("COPY LivesIn FROM 'src/main/resources/lives-in.csv'");
  (do
    (.query conn "CREATE NODE TABLE User(name STRING PRIMARY KEY, age INT64)")
    (.query conn "CREATE NODE TABLE City(name STRING PRIMARY KEY, population INT64)")
    (.query conn "CREATE REL TABLE Follows(FROM User TO User, since INT64)")
    (.query conn "CREATE REL TABLE LivesIn(FROM User TO City)")
    (.query conn "COPY User FROM 'resources/user.csv'")
    (.query conn "COPY City FROM 'resources/city.csv'")
    (.query conn "COPY Follows FROM 'resources/follows.csv'")
    (.query conn "COPY LivesIn FROM 'resources/lives-in.csv'"))

  ;; https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/DataTypeID.java
  (= DataTypeID/STRING DataTypeID/STRING) => true
  DataTypeID/STRING ;; => #object[com.kuzudb.DataTypeID 0x7d7f248d "STRING"]
  (.value DataTypeID/STRING) => 50
  (.value DataTypeID/INT64) => 23

  ;; // Execute a simple query.
  ;; QueryResult result =
  ;;         conn.query("MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;");
  (query conn
         "MATCH (a:User)-[f:Follows]->(b:User) RETURN a.name, f.since, b.name;")

  (let [q "match (a:User)-[f:Follows]->(b:User) where f.since < $year
           return a.name, f.since, b.name;"]
    (execute conn q {:year 2021}))

  (def ps (prepare conn "match (a:User) return a.*"))
  (execute conn ps {})
  (execute conn "create (a:User {name: $name, age: $age})" {:name "John" :age 21})
  (execute conn ps {})
  (execute conn "match (a:User {name: $name}) detach delete a" {:name "John"})
  (execute conn ps {})

  ;; Why ist extra parameter in the map a problem? One gets an error
  ;; message: "Parameter ... not found". See GitHub issue [1].
  ;;
  ;; [1] https://github.com/kuzudb/kuzu/issues/5937
  (execute conn "match (a:User {name: $name}) return a.*" {:name "Adam" :age 22}) ; => Parameter age not found.
  (execute conn "match (a:User {name: $name}) return a.*" {:name "Adam"}) => ({:a.name "Adam", :a.age 30})

  ;; Cypher has many types, noteably lists and maps, and several kinds
  ;; of types, see section "Types, lists and maps" of the OpenCypher
  ;; spec [0]. Kuzu has LIST and STRUCT and a distinct MAP [2,3]. Some
  ;; Kuzu values are opaque so that `.getValue` fails see `try/catch`
  ;; logic in `as-maps`. Do we want/need to convert them to Clojure
  ;; maps?  Isn't Kuzu `Value` serializable to JSON?  If so, it must
  ;; be representable as Clojure map as well. See `ValueNodeUtil` &
  ;; `ValueRelUtil` [1], eventually.
  ;;
  ;; Kuzu `STRUCT` is probably the best candidate to be represented as
  ;; a Clojure map.
  ;;
  ;; [0] https://github.com/opencypher/openCypher/blob/main/cip/0.baseline/openCypher9.pdf
  ;; [1] https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/ValueNodeUtil.java
  ;; [2] https://github.com/kuzudb/kuzu/blob/master/tools/java_api/src/main/java/com/kuzudb/DataTypeID.java
  ;; [3] https://docs.kuzudb.com/cypher/data-types/
  (for [q ["return [7, 42] as a"
           "return {x: 7, y: 42} as a"
           "return map([1, 2], ['x', 'y']) as a"
           "return map(['x', 'y'], [1, 2]) as a"]]
    (let [{:keys [a]} (query conn q)]
      a))
  => ([7 42] {:x 7, :y 42} {1 "x", 2 "y"} {"x" 1, "y" 2})

  (let [as (execute conn "match (a:User {name: $name}) return a" {:name "Adam"})]
    (for [a as :let [a (:a a)]]
      (inspect-value a)))
  => (("NODE" "{_ID: 0:0, _LABEL: User, name: Adam, age: 30}"))

  (let [as (query conn "match ()-[a:Follows]->() return a limit 1")]
    (for [a as :let [a (:a a)]]
      (inspect-value a)))
  => (("REL" "(0:0)-{_LABEL: Follows, _ID: 2:0, since: 2020}->(0:1)"))

  ;; FIXME: This likely illegal syntax, but it crashes Kuzu/JVM
  ;; runtime.  Will we ever need to pass nodes or relations back?
  ;; According to OpenCypher docs, `NODE`, `RELATIONSHIP` and `PATH`
  ;; are "structural types" that "can be returned from queries"
  ;; but "cannot be used as parameters".
  (let [adams (query conn "match (a:User {name: 'Adam'}) return a")]
    (for [a adams]
      (execute conn "match (a) where a = $a return a.name" a)))

  ;; FWIW: This syntax appears to be OK, also in parametrized form for
  ;; `prepare`:
  (query conn "match (a) where a = a return a")
  (prepare conn "match (a) where a = $a return a")

  ;; FIXME: multiple returns or `.hasNextQueryResult`. This would be a
  ;; simplest example:
  (query conn "return 1 as x;") => ({:x 1})                ; OK
  (query conn "return 1 as x; return 2 as y;") => ({:x 1}) ; FAIL

  ;; Close and release the underlying resources. This method is
  ;; invoked automatically on objects managed by the
  ;; try-with-resources statement.
  (.close conn)
  (.close db)
  nil)
