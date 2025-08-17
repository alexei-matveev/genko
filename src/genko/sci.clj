(ns genko.sci
  (:require
   [sci.core :as sci]))

(defn eval-string [clojure-code]
  (sci/eval-string clojure-code
                   {:namespaces {'Math {'sin Math/sin
                                        'cos Math/cos
                                        'pow Math/pow}}}))

;; To get an idea about "attack surface" without explicit allow-list:
;;
;; (eval-string "(all-ns)") =>
;; (#object[sci.lang.Namespace ... "user"]
;;  #object[sci.lang.Namespace ... "clojure.core"]
;;  #object[sci.lang.Namespace ... "clojure.set"]
;;  #object[sci.lang.Namespace ... "Math"]               <- We created that!
;;  #object[sci.lang.Namespace ... "clojure.edn"]
;;  #object[sci.lang.Namespace ... "clojure.repl"]
;;  #object[sci.lang.Namespace ... "clojure.string"]
;;  #object[sci.lang.Namespace ... "clojure.walk"]
;;  #object[sci.lang.Namespace ... "clojure.template"])
(comment
  (eval-string "(count (all-ns))") => 9
  (count (eval-string "(clojure.repl/dir-fn (find-ns 'user))")) => 0
  (count (eval-string "(clojure.repl/dir-fn (find-ns 'Math))")) => 3
  (count (eval-string "(clojure.repl/dir-fn (find-ns 'clojure.core))")) => 563
  (count (eval-string "(clojure.repl/dir-fn (find-ns 'clojure.repl))")) => 10)
