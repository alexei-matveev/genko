(ns genko.core-test
  (:require [clojure.test :refer :all]
            [genko.core :refer :all]))

(deftest a-test
  (testing "Testing 2 * 2 = 4"
    (is (= 4 (* 2 2)))))
