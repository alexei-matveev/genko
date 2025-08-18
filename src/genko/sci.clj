(ns genko.sci
  "So far a very slim interface to sandboxed Babashka Small Clojure
  Interpreter (SCI)"
  (:require
   [sci.core :as sci]))


(defn eval-string [clojure-code]
  (sci/eval-string clojure-code
                   {:namespaces {'Math {'sin Math/sin
                                        'cos Math/cos
                                        'pow Math/pow}}}))

;; For debugging:
(defn- eval-sexp [sexp]
  (eval-string (str sexp)))

;; To get an idea about "attack surface" without explicit
;; allow-list. Much the staff here is to decipher the *ctx* issue with
;; lazy seqs.
(comment
  ;; NOTE: Closures such as (fn [] (find-ns 'user)) or calls to the
  ;; likes of `find-ns` rely on "dynamic SCI context",
  ;; sci.ctx-store/*ctx*. I dont quite feel it, but see the issue &
  ;; changelog in the upstream project [1, 2].
  ;;
  ;; The really  important suff  is that  with lazy  sequences leaking
  ;; outside of SCI  the tools like `find-ns` are  not more guaranteed
  ;; to find the correct context in dynamic var `*ctx*` [3]. Hence the
  ;; error message: "No context found in: sci.ctx-store/*ctx* ..."
  ;;
  ;; [1] https://github.com/babashka/sci/issues/989
  ;; [2] https://github.com/babashka/sci/blob/master/CHANGELOG.md#01046-2025-06-18
  ;; [3] https://github.com/babashka/sci?tab=readme-ov-file#laziness

  ;; (def _ctx (sci/init {}))
  ;; (keys _ctx) =>
  ;; (:bindings :env :features :readers :reload-all :check-permissions
  ;;  :allow :deny :reify-fn :proxy-fn :main-thread-id)
  ;; This calls `alter-var-root` for dynamic var `*ctx*`:
  ;; (sci.ctx-store/reset-ctx! _ctx)
  ;; (sci.ctx-store/reset-ctx! nil)

  (eval-sexp '(count (all-ns))) => 9
  (eval-sexp '(mapv str (all-ns)))
  =>
  ["user" "clojure.core" "clojure.set"
   "Math"                               ; <- we created that!
   "clojure.edn" "clojure.repl" "clojure.string" "clojure.walk" "clojure.template"]

  ;; Number of bindings pro namespace. NOTE: `doall` *inside* SCI is
  ;; important for lazy sequence such as the `for`-expression here!
  ;; Once you leave the dynamic context of the SCI you cannot count on
  ;; dynamic `*ctx*` having a correct value!
  ;;
  ;;     (eval-sexp '(for [ns ['user]] (find-ns ns)))   ; WRONG!
  ;;     => Exception with "No context found in: sci.ctx-store/*ctx* ..."
  (eval-sexp
   '(doall
     (for [ns (all-ns)]
       [(str ns) (count (clojure.repl/dir-fn ns))])))
  =>
  (["user" 0]
   ["clojure.core" 563]
   ["clojure.set" 12]
   ["Math" 3]                           ; 0 <> 3 without `doall`
   ["clojure.edn" 2]
   ["clojure.repl" 10]
   ["clojure.string" 21]
   ["clojure.walk" 10]
   ["clojure.template" 2])

  ;; Alternatively use `mapv` instead of lazy seq:
  (eval-sexp
   '(let [dirs (mapv clojure.repl/dir-fn (all-ns))]
      (mapv count dirs))) => [0 563 12 3 2 10 21 10 2])
