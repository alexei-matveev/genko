(ns genko.sci
  (:require
   [sci.core :as sci]))

;; (genko.sci/eval-string "(all-ns)") =>
;; (#object[sci.lang.Namespace ... "user"]
;;  #object[sci.lang.Namespace ... "clojure.core"]
;;  #object[sci.lang.Namespace ... "clojure.set"]
;;  #object[sci.lang.Namespace ... "Math"]
;;  #object[sci.lang.Namespace ... "clojure.edn"]
;;  #object[sci.lang.Namespace ... "clojure.repl"]
;;  #object[sci.lang.Namespace ... "clojure.string"]
;;  #object[sci.lang.Namespace ... "clojure.walk"]
;;  #object[sci.lang.Namespace ... "clojure.template"])
(defn eval-string [clojure-code]
  (sci/eval-string clojure-code
                   {:namespaces {'Math {'sin Math/sin
                                        'cos Math/cos
                                        'pow Math/pow}}}))
