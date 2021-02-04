;; [[file:../../org/GridFire.org::command-line-interface][command-line-interface]]
(ns gridfire.cli
  (:gen-class)
  (:require [clojure.core.matrix :as m]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [gridfire.binary-output :as binary]
            [gridfire.common :refer [in-bounds?]]
            [gridfire.crown-fire :refer [m->ft]]
            [gridfire.common :refer [in-bounds?]]
            [gridfire.fetch :as fetch]
            [gridfire.fire-spread :refer [get-neighbors run-fire-spread]]
            [gridfire.magellan-bridge :refer [geotiff-raster-to-matrix]]
            [gridfire.perturbation :as perturbation]
            [gridfire.postgis-bridge :refer [postgis-raster-to-matrix]]
            [gridfire.spec.config :as spec]
            [gridfire.surface-fire :refer [degrees-to-radians]]
            [gridfire.utils.random :refer [my-rand-int my-rand-nth]]
            [magellan.core :refer [make-envelope
                                   matrix-to-raster
                                   register-new-crs-definitions-from-properties-file!
                                   write-raster]]
            [matrix-viz.core :refer [save-matrix-as-png]])
  (:import java.util.Random))

(m/set-current-implementation :vectorz)

(register-new-crs-definitions-from-properties-file! "CUSTOM"
                                                    (io/resource "custom_projections.properties"))

(def layer-names
  [:aspect
   :canopy-base-height
   :canopy-cover
   :canopy-height
   :crown-bulk-density
   :elevation
   :fuel-model
   :slope])

(defn convert-metrics
  "Converting metrics in layers:
  meters to feet
  degrees to percent"
  [landfire-layers]
  (-> landfire-layers
      (update-in [:elevation :matrix]
                 (fn [matrix] (m/emap #(* % 3.28) matrix))) ; m -> ft
      (update-in [:slope :matrix]
                 (fn [matrix] (m/emap #(Math/tan (degrees-to-radians %)) matrix))) ; degrees -> %
      (update-in [:canopy-height :matrix]
                 (fn [matrix] (m/emap #(* % 3.28) matrix))) ; m -> ft
      (update-in [:canopy-base-height :matrix]
                 (fn [matrix] (m/emap #(* % 3.28) matrix))) ; m -> ft
      (update-in [:crown-bulk-density :matrix]
                 (fn [matrix] (m/emap #(* % 0.0624) matrix))))) ; kg/m^3 -> lb/ft^3

(defmulti fetch-landfire-layers
  "Returns a map of LANDFIRE rasters (represented as maps) with the following units:
   {:elevation          feet
    :slope              vertical feet/horizontal feet
    :aspect             degrees clockwise from north
    :fuel-model         fuel model numbers 1-256
    :canopy-height      feet
    :canopy-base-height feet
    :crown-bulk-density lb/ft^3
    :canopy-cover       % (0-100)}"
  (fn [config]
    (:fetch-layer-method config)))

(defmethod fetch-landfire-layers :postgis
  [{:keys [db-spec landfire-layers]}]
  (convert-metrics
   (reduce (fn [amap layer-name]
             (let [table (landfire-layers layer-name)]
               (assoc amap layer-name
                      (postgis-raster-to-matrix db-spec table))))
           {}
           layer-names)))

(defmethod fetch-landfire-layers :geotiff
  [{:keys [landfire-layers]}]
  (convert-metrics
   (reduce (fn [amap layer-name]
             (let [file-name (landfire-layers layer-name)]
               (assoc amap layer-name
                      (geotiff-raster-to-matrix file-name))))
           {}
           layer-names)))

(defn sample-from-list
  [rand-generator n xs]
  (repeatedly n #(my-rand-nth rand-generator xs)))

(defn sample-from-range
  [rand-generator n [min max]]
  (let [range (- max min)]
    (repeatedly n #(+ min (my-rand-int rand-generator range)))))

(defn draw-samples
  [rand-generator n x]
  (into []
        (cond (list? x)   (sample-from-list rand-generator n x)
              (vector? x) (sample-from-range rand-generator n x)
              :else       (repeat n x))))

(defn cells-to-acres
  [cell-size num-cells]
  (let [acres-per-cell (/ (* cell-size cell-size) 43560.0)]
    (* acres-per-cell num-cells)))

(defn summarize-fire-spread-results
  [fire-spread-results cell-size]
  (let [flame-lengths              (filterv pos? (m/eseq (:flame-length-matrix fire-spread-results)))
        fire-line-intensities      (filterv pos? (m/eseq (:fire-line-intensity-matrix fire-spread-results)))
        burned-cells               (count flame-lengths)
        fire-size                  (cells-to-acres cell-size burned-cells)
        flame-length-mean          (/ (m/esum flame-lengths) burned-cells)
        fire-line-intensity-mean   (/ (m/esum fire-line-intensities) burned-cells)
        flame-length-stddev        (->> flame-lengths
                                        (m/emap #(Math/pow (- flame-length-mean %) 2.0))
                                        (m/esum)
                                        (#(/ % burned-cells))
                                        (Math/sqrt))
        fire-line-intensity-stddev (->> fire-line-intensities
                                        (m/emap #(Math/pow (- fire-line-intensity-mean %) 2.0))
                                        (m/esum)
                                        (#(/ % burned-cells))
                                        (Math/sqrt))]
    {:fire-size                  fire-size
     :flame-length-mean          flame-length-mean
     :flame-length-stddev        flame-length-stddev
     :fire-line-intensity-mean   fire-line-intensity-mean
     :fire-line-intensity-stddev fire-line-intensity-stddev}))

(defn calc-emc
  "Computes the Equilibrium Moisture Content (EMC) from rh (relative
   humidity in %) and temp (temperature in F)."
  [rh temp]
  (/ (cond (< rh 10)  (+ 0.03229 (* 0.281073 rh) (* -0.000578 rh temp))
           (< rh 50)  (+ 2.22749 (* 0.160107 rh) (* -0.01478 temp))
           :otherwise (+ 21.0606 (* 0.005565 rh rh) (* -0.00035 rh temp) (* -0.483199 rh)))
     30))

(defn calc-ffwi
  "Computes the Fosberg Fire Weather Index value from rh (relative
   humidity in %), temp (temperature in F), wsp (wind speed in mph),
   and a constant x (gust multiplier).
   ------------------------------------------------------------------
   Note: ffwi can be computed with (calc-ffwi rh temp wsp 1.0)
         ffwi-max can be computed with (calc-ffwi minrh maxtemp wsp 1.75)
   Geek points: Uses Cramer's rule: (+ d (* x (+ c (* x (+ b (* x a))))))
                for an efficient cubic calculation on tmp."
  [rh temp wsp x]
  (let [m   (calc-emc rh temp)
        eta (+ 1 (* m (+ -2 (* m (+ 1.5 (* m -0.5))))))]
    (/ (* eta (Math/sqrt (+ 1 (Math/pow (* x wsp) 2))))
       0.3002)))

(defn kebab->snake [s]
  (str/replace s #"-" "_"))

(defn snake->kebab [s]
  (str/replace s #"_" "-"))

(defn min->hour [t]
  (int (quot t 60)))

(defn previous-active-perimeter?
  [[i j :as here] matrix]
  (let [num-rows (m/row-count matrix)
        num-cols (m/column-count matrix)]
   (and
    (= (m/mget matrix i j) -1.0)
    (->> (get-neighbors here)
         (filter #(in-bounds? num-rows num-cols %))
         (map #(apply m/mget matrix %))
         (some pos?)))))

(defn to-color-map-values [burn-time-matrix current-clock]
  (m/emap-indexed (fn [here burn-time]
                    (let [delta-hours (->> (- current-clock burn-time)
                                           min->hour)]
                      (cond
                        (previous-active-perimeter? here burn-time-matrix) 201
                        (= burn-time -1.0)                                 200
                        (< 0 delta-hours 5)                                delta-hours
                        (>= delta-hours 5)                                 5
                        :else                                              0)))
                  burn-time-matrix))

(defn layer-snapshot [burn-time-matrix layer-matrix t]
  (m/emap (fn [layer-value burn-time]
            (if (<= burn-time t)
              layer-value
              0))
          layer-matrix
          burn-time-matrix))

(defn output-filename [name outfile-suffix simulation-id output-time ext]
  (as-> [name outfile-suffix simulation-id (when output-time (str "t" output-time))] $
       (remove str/blank? $)
       (str/join "_" $)
       (str $ ext)))

(defn output-path
  [output-directory name outfile-suffix simulation-id output-time ext]
  (let [filename (output-filename name
                                  outfile-suffix
                                  (str simulation-id)
                                  output-time
                                  ext)]
    (if output-directory
      (str/join "/" [output-directory filename])
      filename)))

(defn output-geotiff
  ([config matrix name envelope]
   (output-geotiff config matrix name envelope nil nil))

  ([config matrix name envelope simulation-id]
   (output-geotiff config matrix name envelope simulation-id nil))

  ([{:keys [output-geotiffs? outfile-suffix output-directory] :as config}
    matrix name envelope simulation-id output-time]
   (when output-geotiffs?
     (-> (matrix-to-raster name matrix envelope)
         (write-raster (output-path output-directory
                                    name
                                    outfile-suffix
                                    (str simulation-id)
                                    output-time
                                    ".tif"))))))

(defn output-png
  ([config matrix name envelope]
   (output-png config matrix name envelope nil nil))

  ([config matrix name envelope simulation-id]
   (output-png config matrix name envelope simulation-id nil))

  ([{:keys [output-png? outfile-suffix output-directory] :as config}
    matrix name envelope simulation-id output-time]
   (when output-png?
     (save-matrix-as-png :color 4 -1.0
                         matrix
                         (output-path output-directory
                                      name
                                      outfile-suffix
                                      (str simulation-id)
                                      output-time
                                      ".png")))))

(def layer-name->matrix
  [["fire_spread"         :fire-spread-matrix]
   ["flame_length"        :flame-length-matrix]
   ["fire_line_intensity" :fire-line-intensity-matrix]
   ["burn_history"        :burn-time-matrix]
   ["spread_rate"         :spread-rate-matrix]
   ["fire_type"           :fire-type-matrix]])

(defn filter-output-layers [output-layers]
  (let [layers-to-filter (set (map (comp kebab->snake name) (keys output-layers)))]
    (filter (fn [[name _]] (contains? layers-to-filter name)) layer-name->matrix)))

(defn process-output-layers-timestepped
  [{:keys [simulation-id] :as config}
   {:keys [global-clock burn-time-matrix] :as fire-spread-results}
   name layer timestep envelope]
  (doseq [output-time (range 0 (inc global-clock) timestep)]
    (let [matrix          (if (= layer "burn_history")
                            (to-color-map-values layer output-time)
                            (fire-spread-results layer))
          filtered-matrix (layer-snapshot burn-time-matrix matrix output-time)]
      (output-geotiff config filtered-matrix name envelope simulation-id output-time)
      (output-png config filtered-matrix name envelope simulation-id output-time))))

(defn process-output-layers!
  [{:keys [output-layers] :as config}
   {:keys [global-clock burn-time-matrix] :as fire-spread-results}
   envelope
   simulation-id]
  (let [layers (if output-layers
                 (filter-output-layers output-layers)
                 layer-name->matrix)]
    (doseq [[name layer] layers]
      (let [kw       (keyword (snake->kebab name))
            timestep (get output-layers kw)]
        (if (int? timestep)
          (process-output-layers-timestepped config
                                             fire-spread-results
                                             name
                                             layer
                                             timestep
                                             envelope)
          (let [matrix (if (= layer "burn_history")
                         (to-color-map-values layer global-clock)
                         (fire-spread-results layer))]
            (output-geotiff config matrix name envelope simulation-id)
            (output-png config matrix name envelope simulation-id)))))))

(defn process-burn-count!
  [{:keys [fire-spread-matrix burn-time-matrix global-clock]}
   burn-count-matrix
   timestep]
  (if (int? timestep)
   (doseq [clock (range 0 (inc global-clock) timestep)]
     (let [filtered-fire-spread (m/emap (fn [layer-value burn-time]
                                          (if (<= burn-time clock)
                                            layer-value
                                            0))
                                        fire-spread-matrix
                                        burn-time-matrix)
           band                 (int (quot clock timestep))]
       (m/add! (nth (seq burn-count-matrix) band) filtered-fire-spread)))
   (m/add! burn-count-matrix fire-spread-matrix)))

(defn output-burn-probability-layer!
  [{:keys [output-burn-probability simulations] :as config} envelope burn-count-matrix]
  (when-let [timestep output-burn-probability]
    (let [output-name "burn_probability"]
      (if (int? timestep)
        (doseq [[band matrix] (map-indexed vector burn-count-matrix)]
          (let [output-time        (* band timestep)
                probability-matrix (m/emap #(/ % simulations) matrix)]
            (output-geotiff config probability-matrix output-name envelope nil output-time)
            (output-png config probability-matrix output-name envelope nil output-time)))
        (let [probability-matrix (m/emap #(/ % simulations) burn-count-matrix)]
          (output-geotiff config probability-matrix output-name envelope)
          (output-png config probability-matrix output-name envelope))))))

(defn initialize-burn-count-matrix
  [{:keys [output-burn-probability]} max-runtime num-rows num-cols]
  (when output-burn-probability
    (if (int? output-burn-probability)
     (let [num-bands (inc (quot (apply max max-runtime) output-burn-probability))]
       (m/zero-array [num-bands num-rows num-cols]))
     (m/zero-array [num-rows num-cols]))))

(defn process-binary-outputs!
  [{:keys [output-binary? output-directory]}
   {:keys [burn-time-matrix flame-length-matrix spread-rate-matrix fire-type-matrix]} simulation]
  (when output-binary?
    (let [output-name (str "simulation_" simulation ".bin")]
      (binary/write-matrices-as-binary [burn-time-matrix
                                        flame-length-matrix
                                        spread-rate-matrix
                                        fire-type-matrix]
                                       (if output-directory
                                         (str/join "/" [output-directory output-name])
                                         output-name)))))

(defn run-simulations
  [{:keys
    [cell-size outfile-suffix output-csvs? fetch-temperature-method
     fetch-wind-speed-20ft-method fetch-wind-from-direction-method
     fetch-relative-humidity-method simulations output-layers output-burn-probability] :as config}
   landfire-rasters envelope ignition-row
   ignition-col max-runtime temperature relative-humidity wind-speed-20ft
   wind-from-direction foliar-moisture ellipse-adjustment-factor ignition-raster
   multiplier-lookup perturbations burn-count-matrix]
  (mapv
   (fn [i]
     (let [initial-ignition-site (or ignition-raster
                                     [(ignition-row i) (ignition-col i)])
           temperature           (if fetch-temperature-method temperature (temperature i))
           wind-speed-20ft       (if fetch-wind-speed-20ft-method wind-speed-20ft (wind-speed-20ft i))
           wind-from-direction   (if fetch-wind-from-direction-method wind-from-direction (wind-from-direction i))
           relative-humidity     (if fetch-relative-humidity-method relative-humidity (relative-humidity i))]
       (if-let [fire-spread-results (run-fire-spread
                                     {:max-runtime               (max-runtime i)
                                      :cell-size                 cell-size
                                      :landfire-layers           landfire-rasters
                                      :wind-speed-20ft           wind-speed-20ft
                                      :wind-from-direction       wind-from-direction
                                      :temperature               temperature
                                      :relative-humidity         relative-humidity
                                      :foliar-moisture           (* 0.01 (foliar-moisture i))
                                      :ellipse-adjustment-factor (ellipse-adjustment-factor i)
                                      :num-rows                  (m/row-count (:fuel-model landfire-rasters))
                                      :num-cols                  (m/column-count (:fuel-model landfire-rasters))
                                      :multiplier-lookup         multiplier-lookup
                                      :perturbations             (when perturbations
                                                                   (perturbations i))}
                                     config
                                     initial-ignition-site)]
         (do
           (process-output-layers! config fire-spread-results envelope i)
           (when-let [timestep output-burn-probability]
             (process-burn-count! fire-spread-results burn-count-matrix timestep))
           (process-binary-outputs! config fire-spread-results i)
           (if output-csvs?
             (merge
              {:ignition-row              (ignition-row i)
               :ignition-col              (ignition-col i)
               :ignition-raster           ignition-raster
               :max-runtime               (max-runtime i)
               :temperature               temperature
               :relative-humidity         relative-humidity
               :wind-speed-20ft           wind-speed-20ft
               :wind-from-direction       wind-from-direction
               :foliar-moisture           (foliar-moisture i)
               :ellipse-adjustment-factor (ellipse-adjustment-factor i)}
              (summarize-fire-spread-results fire-spread-results cell-size))))
         (when output-csvs?
           {:ignition-row               (ignition-row i)
            :ignition-col               (ignition-col i)
            :ignition-raster            ignition-raster
            :max-runtime                (max-runtime i)
            :temperature                temperature
            :relative-humidity          relative-humidity
            :wind-speed-20ft            wind-speed-20ft
            :wind-from-direction        wind-from-direction
            :foliar-moisture            (foliar-moisture i)
            :ellipse-adjustment-factor  (ellipse-adjustment-factor i)
            :fire-size                  0.0
            :flame-length-mean          0.0
            :flame-length-stddev        0.0
            :fire-line-intensity-mean   0.0
            :fire-line-intensity-stddev 0.0}))))
   (range simulations)))

(defn write-csv-outputs
  [output-directory output-csvs? output-filename results-table]
  (when output-csvs?
    (with-open [out-file (io/writer (when output-directory
                                      (str/join "/" [output-directory output-filename])
                                      output-filename))]
      (->> results-table
           (sort-by #(vector (:ignition-row %) (:ignition-col %)))
           (mapv (fn [{:keys [ignition-row ignition-col max-runtime temperature relative-humidity wind-speed-20ft
                              wind-from-direction foliar-moisture ellipse-adjustment-factor fire-size flame-length-mean
                              flame-length-stddev fire-line-intensity-mean fire-line-intensity-stddev]}]
                   [ignition-row
                    ignition-col
                    max-runtime
                    temperature
                    relative-humidity
                    wind-speed-20ft
                    wind-from-direction
                    foliar-moisture
                    ellipse-adjustment-factor
                    fire-size
                    flame-length-mean
                    flame-length-stddev
                    fire-line-intensity-mean
                    fire-line-intensity-stddev]))
           (cons ["ignition-row" "ignition-col" "max-runtime" "temperature" "relative-humidity" "wind-speed-20ft"
                  "wind-from-direction" "foliar-moisture" "ellipse-adjustment-factor" "fire-size" "flame-length-mean"
                  "flame-length-stddev" "fire-line-intensity-mean" "fire-line-intensity-stddev"])
           (csv/write-csv out-file)))))

(defn get-envelope
  [config landfire-layers]
  (let [{:keys [upperleftx upperlefty width height scalex scaley]} (landfire-layers :elevation)]
    (make-envelope (:srid config)
                   upperleftx
                   (+ upperlefty (* height scaley))
                   (* width scalex)
                   (* -1.0 height scaley))))

(defn get-weather
  ([config rand-generator weather-type]
   (get-weather config rand-generator weather-type nil))

  ([config rand-generator weather-type weather-layers]
   (if (and weather-layers (contains? weather-layers weather-type))
     (get-in weather-layers [weather-type :matrix])
     (draw-samples rand-generator (:simulations config) (config weather-type)))))

(defn fetch-weather-layers [config]
  (reduce (fn [acc layer]
            (let [fetch-method (keyword (str/join "-" ["fetch" (name layer) "method"]))]
              (if (contains? config fetch-method)
                (assoc acc layer (fetch/weather config layer))
                acc)))
          {}
          spec/weather-names))

(defn create-multiplier-lookup
  [{:keys [cell-size] :as config} weather-layers]
  (reduce-kv (fn [acc k {:keys [scalex]}]
               (assoc acc k (int (quot (m->ft scalex) cell-size))))
             {}
             weather-layers))

(defn -main
  [& config-files]
  (doseq [config-file config-files]
    (let [config (edn/read-string (slurp config-file))]
      (if (s/valid? ::spec/config config)
        (let [landfire-layers   (fetch-landfire-layers config)
              landfire-rasters  (into {}
                                      (map (fn [[layer info]] [layer (:matrix info)]))
                                      landfire-layers)
              ignition-raster   (fetch/initial-ignition-layers config)
              weather-layers    (fetch-weather-layers config)
              multiplier-lookup (create-multiplier-lookup config weather-layers)
              envelope          (get-envelope config landfire-layers)
              simulations       (:simulations config)
              rand-generator    (if-let [seed (:random-seed config)]
                                  (Random. seed)
                                  (Random.))
              max-runtimes      (draw-samples rand-generator simulations (:max-runtime config))
              num-rows          (m/row-count (:fuel-model landfire-rasters))
              num-cols          (m/column-count (:fuel-model landfire-rasters))
              burn-count-matrix (initialize-burn-count-matrix config max-runtimes num-rows num-cols)]
          (when (:output-landfire-inputs? config)
            (doseq [[layer matrix] landfire-rasters]
              (-> (matrix-to-raster (name layer) matrix envelope)
                  (write-raster (str (name layer) (:outfile-suffix config) ".tif")))))
          (->> (run-simulations
                (assoc config :rand-gen rand-generator)
                landfire-rasters
                envelope
                (draw-samples rand-generator simulations (:ignition-row config))
                (draw-samples rand-generator simulations (:ignition-col config))
                max-runtimes
                (get-weather config rand-generator :temperature weather-layers)
                (get-weather config rand-generator :relative-humidity weather-layers)
                (get-weather config rand-generator :wind-speed-20ft weather-layers)
                (get-weather config rand-generator :wind-from-direction weather-layers)
                (draw-samples rand-generator simulations (:foliar-moisture config))
                (draw-samples rand-generator simulations (:ellipse-adjustment-factor config))
                ignition-raster
                multiplier-lookup
                (perturbation/draw-samples rand-generator simulations (:perturbations config))
                burn-count-matrix)
               (write-csv-outputs
                (:output-directory config)
                (:output-csvs? config)
                (str "summary_stats" (:outfile-suffix config) ".csv")))
          (output-burn-probability-layer! config envelope burn-count-matrix))
        (s/explain ::spec/config config)))))
;; command-line-interface ends here
