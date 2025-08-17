(ns genko.sci
  (:require
   [sci.core :as sci]))

(defn eval-string [clojure-code]
  (sci/eval-string clojure-code
                   {:namespaces {'Math {'sin Math/sin
                                        'cos Math/cos
                                        'pow Math/pow}}}))

;; For internal use:
(defn- eval-sexp [sexp]
  (eval-string (str sexp)))

;; To get an idea about "attack surface" without explicit allow-list:
(comment
  (eval-sexp '(count (all-ns))) => 9
  (eval-sexp '(map str (all-ns)))
  =>
  ("user" "clojure.core" "clojure.set"
   "Math"                               ; <- we created that!
   "clojure.edn" "clojure.repl" "clojure.string" "clojure.walk" "clojure.template")

  (eval-sexp '(let [ns 'user] (count (clojure.repl/dir-fn (find-ns ns))))) => 0
  (eval-sexp '(count (clojure.repl/dir-fn (find-ns 'Math)))) => 3
  (eval-sexp '(count (clojure.repl/dir-fn (find-ns 'clojure.core)))) => 563
  (eval-sexp '(count (clojure.repl/dir-fn (find-ns 'clojure.repl)))) => 10

  ;; FIXME: somethig is wrong with (find-ns) inside of a lazy for-loop
  ;; or seq instead of a (strict?) let-form! See the issue in the
  ;; upstream project [1].
  ;;
  ;; [1] https://github.com/babashka/sci/issues/989
  (eval-sexp '(for [ns ['user]] (find-ns ns))) ; => "No context found in: sci.ctx-store/*ctx*"
  (eval-sexp '(map find-ns ['user]))           ; => Same!
  (map find-ns ['user])                        ; => (#namespace[user])
  (eval-sexp '(mapv find-ns ['user])) ; => [#object[sci.lang.Namespace 0x54f40d66 "user"]]

  ;; Even without `find-ns` we still do need strict `mapv` here:
  (eval-sexp
   '(let [ns-objects (all-ns)
          dirs (mapv clojure.repl/dir-fn ns-objects)]
      (map count dirs))) => (0 563 12 3 2 10 21 10 2))
