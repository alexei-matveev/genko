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

  (count (eval-sexp '(clojure.repl/dir-fn (find-ns 'user)))) => 0
  (count (eval-sexp '(clojure.repl/dir-fn (find-ns 'Math)))) => 3
  (count (eval-sexp '(clojure.repl/dir-fn (find-ns 'clojure.core)))) => 563
  (count (eval-sexp '(clojure.repl/dir-fn (find-ns 'clojure.repl)))) => 10

  ;; FIXME: what is wrong with that?
  (eval-sexp
   '(for [ns ['user 'clojure.core]]
      [ns (count (clojure.repl/dir-fn (find-ns ns)))])))
