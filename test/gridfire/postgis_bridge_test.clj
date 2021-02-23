(ns gridfire.postgis-bridge-test
  (:require [gridfire.postgis-bridge :refer [postgis-raster-to-matrix]]
            [clojure.test :refer [deftest is]]))

(def db-spec {:classname   "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname     "//localhost:5432/gridfire_test"
              :user        "gridfire_test"
              :password    "gridfire_test"})

(deftest ^:integration single-band-test
  (let [raster (postgis-raster-to-matrix db-spec "landfire.fbfm40 WHERE rid=1")]

    (is (some? raster))

    (is (= 1 (:numbands raster)))))

(deftest ^:integration multi-band-test
  (let [raster (postgis-raster-to-matrix db-spec "weather.ws WHERE rid=1")]

    (is (some? raster))

    (is (= 73 (:numbands raster)))))

(deftest ^:integration multi-band-rescale-test
  (let [raster (postgis-raster-to-matrix db-spec "weather.ws WHERE rid=1" 600)]

    (is (some? raster))

    (is (= 73 (:numbands raster)))))

(deftest ^:integration multi-band-rescale-threshold-test
  (let [raster (postgis-raster-to-matrix db-spec "weather.ws WHERE rid=1" 600 10)]

    (is (some? raster))

    (is (= 73 (:numbands raster)))))
