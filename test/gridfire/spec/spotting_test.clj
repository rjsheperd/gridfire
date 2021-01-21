(ns gridfire.spec.spotting-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [gridfire.spec.spotting :as spotting]))

(deftest spotting-test
  (let [config {:ambient-gas-density         1.0
                :crown-fire-spotting-percent [0.1 0.8]
                :num-firebrands              10
                :specific-heat-gas           0.1}]
    (is (s/valid? ::spotting/spotting config))))

(deftest crown-fire-spotting-percent-test
  (testing "scalar"
    (is (s/valid? ::spotting/crown-fire-spotting-percent 0.1)))

  (testing "range"
    (is (s/valid? ::spotting/crown-fire-spotting-percent [0.1 0.8])))

  (testing "invalid range"
    (is (not (s/valid? ::spotting/crown-fire-spotting-percent [0.8 0.1]))
        "first value should not be larger than the second")))
