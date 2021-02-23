(ns gridfire.read-weather-test
  (:require [clojure.core.matrix :as m]
            [clojure.java.jdbc   :as jdbc]
            [clojure.test        :refer [deftest is]]
            [gridfire.cli        :as cli]
            [gridfire.fetch      :as fetch]
            [magellan.core       :as magellan]
            [mikera.vectorz.core :as v])
  (:import java.util.Random))

;;-----------------------------------------------------------------------------
;; Config
;;-----------------------------------------------------------------------------

(def resources-path "test/gridfire/resources/weather-test/")

(def db-spec {:classname   "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname     "//localhost:5432/gridfire_test"
              :user        "gridfire_test"
              :password    "gridfire_test"})

(def test-config-base
  {:db-spec     db-spec
   :simulations 1
   :random-seed 1234567890})

;;-----------------------------------------------------------------------------
;; Utils
;;-----------------------------------------------------------------------------

(defn in-file-path [filename]
  (str resources-path filename))

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(deftest ^:integration fetch-temperature-test
  (let [geotiff-file    "tmpf_to_sample.tif"
        geotiff-config  (merge test-config-base
                               {:temperature {:type   :geotiff
                                              :source (in-file-path geotiff-file)}})
        postgis-table   "weather.tmpf WHERE rid=1"
        postgis-config  (merge test-config-base
                               {:temperature {:type   :postgis
                                              :source postgis-table}})
        geotiff-results (fetch/weather geotiff-config (:temperature geotiff-config))
        postgis-results (fetch/weather postgis-config (:temperature postgis-config))]

    (is (every? m/matrix? geotiff-results))

    (is (every? m/matrix? postgis-results))

    (is (= geotiff-results postgis-results))

    (let [numbands (count (:bands (magellan/read-raster (in-file-path geotiff-file))))]
      (is (= numbands (m/dimension-count geotiff-results 0))))

    (let [results  (jdbc/with-db-connection [conn (:db-spec test-config-base)]
                     (jdbc/query conn [(str "SELECT (ST_Metadata(rast)).numbands FROM " postgis-table)]))
          numbands (:numbands (first results))]
      (is (= numbands (m/dimension-count geotiff-results 0))))))

(deftest ^:integration fetch-relative-humidity-test
  (let [geotiff-file    "rh_to_sample.tif"
        geotiff-config  (merge test-config-base
                               {:relative-humidity {:type   :geotiff
                                                    :source (in-file-path geotiff-file)}})
        postgis-table   "weather.rh WHERE rid=1"
        postgis-config  (merge test-config-base
                               {:relative-humidity {:type   :postgis
                                                    :source postgis-table}})
        geotiff-results (fetch/weather geotiff-config (:relative-humidity geotiff-config))
        postgis-results (fetch/weather postgis-config (:relative-humidity postgis-config))]

    (is (every? m/matrix? geotiff-results))

    (is (every? m/matrix? postgis-results))

    (is (= geotiff-results postgis-results))

    (let [numbands (count (:bands (magellan/read-raster (in-file-path geotiff-file))))]
      (is (= numbands (m/dimension-count geotiff-results 0))))

    (let [results  (jdbc/with-db-connection [conn (:db-spec test-config-base)]
                     (jdbc/query conn [(str "SELECT (ST_Metadata(rast)).numbands FROM " postgis-table)]))
          numbands (:numbands (first results))]
      (is (= numbands (m/dimension-count postgis-results 0))))))

(deftest ^:integration fetch-wind-speed-20ft-test
  (let [geotiff-file    "ws_to_sample.tif"
        geotiff-config  (merge test-config-base
                               {:wind-speed-20ft {:type   :geotiff
                                                  :source (in-file-path geotiff-file)}})
        postgis-table   "weather.ws WHERE rid=1"
        postgis-config  (merge test-config-base
                               {:wind-speed-20ft {:type   :postgis
                                                  :source postgis-table}})
        geotiff-results (fetch/weather geotiff-config (:wind-speed-20ft geotiff-config))
        postgis-results (fetch/weather postgis-config (:wind-speed-20ft postgis-config))]

    (is (every? m/matrix? geotiff-results))

    (is (every? m/matrix? postgis-results))

    (is (= geotiff-results postgis-results))

    (let [numbands (count (:bands (magellan/read-raster (in-file-path geotiff-file))))]
      (is (= numbands (m/dimension-count geotiff-results 0))))

    (let [results  (jdbc/with-db-connection [conn (:db-spec test-config-base)]
                     (jdbc/query conn [(str "SELECT (ST_Metadata(rast)).numbands FROM " postgis-table)]))
          numbands (:numbands (first results))]
      (is (= numbands (m/dimension-count postgis-results 0))))))

(deftest ^:integration fetch-wind-from-direction-test
  (let [geotiff-file    "wd_to_sample.tif"
        geotiff-config  (merge test-config-base
                               {:wind-from-direction {:type   :geotiff
                                                      :source (in-file-path geotiff-file)}})
        postgis-table   "weather.wd WHERE rid=1"
        postgis-config  (merge test-config-base
                               {:wind-from-direction {:type   :postgis
                                                      :source postgis-table}})
        geotiff-results (fetch/weather geotiff-config (:wind-from-direction geotiff-config))
        postgis-results (fetch/weather postgis-config (:wind-from-direction postgis-config))]

    (is (every? m/matrix? geotiff-results))

    (is (every? m/matrix? postgis-results))

    (is (= geotiff-results postgis-results))

    (let [numbands (count (:bands (magellan/read-raster (in-file-path geotiff-file))))]
      (is (= numbands (m/dimension-count geotiff-results 0))))

    (let [results  (jdbc/with-db-connection [conn (:db-spec test-config-base)]
                     (jdbc/query conn [(str "SELECT (ST_Metadata(rast)).numbands FROM " postgis-table)]))
          numbands (:numbands (first results))]
      (is (= numbands (m/dimension-count postgis-results 0))))))


(deftest ^:integration get-weather-from-geotiff-test
  (let [file           "ws_to_sample.tif"
        config         (merge test-config-base
                              {:wind-from-direction {:type   :geotiff
                                                     :source (in-file-path file)}})
        rand-generator (Random. (:random-seed config))
        results        (cli/get-weather config rand-generator :wind-from-direction)]

    (is (vector results))

    (is (every? v/vectorz? results))))

(deftest ^:integration get-weather-from-postgis-test
  (let [table          "weather.tmpf WHERE rid=1"
        config         (merge test-config-base
                              {:temperature {:type   :postgis
                                             :source table}})
        rand-generator (Random. (:random-seed config))
        results        (cli/get-weather config rand-generator :temperature)]

    (is (vector results))

    (is (every? v/vectorz? results))))

(deftest ^:unit get-weather-from-range-test
  (let [config         (merge test-config-base
                              {:temperature [0 100]
                               :simulations 10})
        rand-generator (Random. (:random-seed config))
        results        (cli/get-weather config rand-generator :temperature)]

    (is (vector results))

    (is (every? int? results))))


(deftest ^:unit get-weather-from-list-test
  (let [tmp-list       '(0 10 20 30)
        config         (merge test-config-base
                              {:temperature tmp-list
                               :simulations 10})
        rand-generator (Random. (:random-seed config))
        results        (cli/get-weather config rand-generator :temperature)]

    (is (vector results))

    (is (every? int? results))

    (is (= (set results) (set tmp-list)))))

(deftest ^:unit get-weather-scalar-from-test
  (let [config         (merge test-config-base
                              {:temperature 42
                               :simulations 10})
        rand-generator (Random. (:random-seed config))
        results        (cli/get-weather config rand-generator :temperature)]

    (is (vector results))

    (is (every? int? results))

    (is (apply = results))))
