(ns genko.kuzu-test
  (:require [clojure.test :refer :all]
            [genko.kuzu :refer :all]))

(deftest b-test
  (testing "Testing Kuzu (Ladybug?)"
    (let [actual (demo)
          expected '({:a.name "Adam", :f.since 2020, :b.name "Karissa"}
                     {:a.name "Adam", :f.since 2020, :b.name "Zhang"}
                     {:a.name "Karissa", :f.since 2021, :b.name "Zhang"}
                     {:a.name "Zhang", :f.since 2022, :b.name "Noura"})]
      (is (= actual expected)))))
