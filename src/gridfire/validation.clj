(ns gridfire.validation
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]))

;;-----------------------------------------------------------------------------
;; Regex
;;-----------------------------------------------------------------------------

(def postgis-sql-regex #"[a-z0-9]+(\.[a-z0-9]+)? WHERE rid=[0-9]+")
(def path-to-geotiff-regex #"[a-z_\-\s0-9\.]+(\/[a-z_\-\s0-9\.]+)*\.tif")

(s/def ::sql (s/and string? #(re-matches postgis-sql-regex %)))
(s/def ::path (s/and string? #(re-matches path-to-geotiff-regex %)))

;;-----------------------------------------------------------------------------
;; Weather
;;-----------------------------------------------------------------------------

(s/def ::postgis-or-geotiff-map
  (s/keys :req-un [(or ::sql ::path)]
          :opt-un [::cell-size]))

(s/def ::weather
  (s/or :vector (s/coll-of int? :kind vector? :count 2)
        :list (s/coll-of int? :kind list?)
        :string string?
        :map #(s/valid? ::postgis-or-geotiff-map %)))

(s/def ::temperature ::weather)
(s/def ::relative-humidity ::weather)
(s/def ::wind-speed-20ft ::weather)
(s/def ::wind-from-direction ::weather)

(s/def ::weather-layers
  (s/keys
   :req-un [::temperature ::relative-humidity ::wind-speed-20ft ::wind-from-direction]))

(def weather-names
  [:temperature :relative-humidity :wind-speed-20ft :wind-from-direction])

(defn multiple?
  [target cell-size]
  (<= 0.0 (mod cell-size target) 0.002))

(defn valid-weather-cell-sizes?
  [{:keys [cell-size] :as config}]
  (let [weather    (map config weather-names)
        cell-sizes (->> weather
                        (remove nil?)
                        (filter #(= :map (first %)))
                        (map (comp :cell-size second)))]
    (every? #(multiple? cell-size %) cell-sizes)))

(defn valid-weather-fetch-methods?
  [config]
  (let [f               (fn [k] (keyword (str/join "-" ["fetch" (name k) "method"])))
        weather-rasters (->> weather-names
                             (select-keys config)
                             (filter (fn [[_ v]] (or (= (key v) :string)
                                                     (= (key v) :map)))))

        fetch-keywords         (map f (keys weather-rasters))
        missing-fetch-keywords (->> fetch-keywords
                                    (filter #(not (contains? config %)))
                                    seq)]
    (nil? missing-fetch-keywords)))

;;-----------------------------------------------------------------------------
;; Landfire Layers
;;-----------------------------------------------------------------------------

(s/def ::sql-or-path (s/or :sql #(s/valid? ::sql %)
                           :path #(s/valid? ::path %)))
(s/def ::aspect ::sql-or-path)
(s/def ::canopy-base-height ::sql-or-path)
(s/def ::canopy-cover ::sql-or-path)
(s/def ::canopy-height ::sql-or-path)
(s/def ::crown-bulk-density ::sql-or-path)
(s/def ::elevation ::sql-or-path)
(s/def ::fuel-model ::sql-or-path)
(s/def ::slope ::sql-or-path)
(s/def ::cell-size float?)

(s/def ::landfire-layers
  (s/keys
   :req-un [::aspect
            ::canopy-base-height
            ::canopy-cover
            ::canopy-height
            ::crown-bulk-density
            ::elevation
            ::fuel-model
            ::slope]))

(s/def ::config
  (s/and
   (s/keys
    :req-un [::cell-size
             ::landfire-layers])
   ::weather-layers
   #(valid-weather-cell-sizes? %)
   #(valid-weather-fetch-methods? %)))