;; [[file:../../org/GridFire.org::fire-spread-algorithm][fire-spread-algorithm]]
(ns gridfire.fire-spread
  (:require [clojure.core.matrix           :as m]
            [clojure.core.matrix.operators :as mop]
            [gridfire.fuel-models          :refer [build-fuel-model moisturize]]
            [gridfire.surface-fire         :refer [rothermel-surface-fire-spread-no-wind-no-slope
                                                   rothermel-surface-fire-spread-max
                                                   rothermel-surface-fire-spread-any
                                                   anderson-flame-depth byram-fire-line-intensity
                                                   byram-flame-length wind-adjustment-factor]]
            [gridfire.crown-fire           :refer [van-wagner-crown-fire-initiation?
                                                   cruz-crown-fire-spread
                                                   crown-fire-line-intensity
                                                   crown-fire-eccentricity]]))

(m/set-current-implementation :vectorz)

;; for surface fire, tau = 10 mins, t0 = 0, and t = global-clock
;; for crown fire, tau = 20 mins, t0 = time of first torch, t = global-clock
;; (defn lautenberger-spread-acceleration
;;   [equilibrium-spread-rate t0 t tau]
;;   (* equilibrium-spread-rate (- 1.0 (Math/exp (/ (- t0 t 0.2) tau)))))
;;
;; Note: Because of our use of adaptive timesteps, if the spread rate on
;;       the first timestep is not at least 83 ft/min, then the timestep will
;;       be calculated as greater than 60 minutes, which will terminate the
;;       one hour fire simulation instantly.

(defn random-cell
  "Returns a random [i j] pair with i < num-rows and j < num-cols."
  [num-rows num-cols]
  [(rand-int num-rows)
   (rand-int num-cols)])

(defn get-neighbors
  "Returns the eight points adjacent to the passed-in point."
  [[i j]]
  (let [i- (- i 1)
        i+ (+ i 1)
        j- (- j 1)
        j+ (+ j 1)]
    (vector [i- j-] [i- j] [i- j+]
            [i  j-]        [i  j+]
            [i+ j-] [i+ j] [i+ j+])))

(defn in-bounds?
  "Returns true if the point lies within the bounds [0,rows) by [0,cols)."
  [rows cols [i j]]
  (and (>= i 0)
       (>= j 0)
       (< i rows)
       (< j cols)))

(defn burnable-fuel-model?
  [^double number]
  (and (pos? number)
       (or (< number 91.0)
           (> number 99.0))))

(defn burnable?
  "Returns true if cell [i j] has not yet been ignited (but could be)."
  [fire-spread-matrix fuel-model-matrix [i j]]
  (and (zero? (m/mget fire-spread-matrix i j))
       (burnable-fuel-model? (m/mget fuel-model-matrix i j))))

(defn distance-3d
  "Returns the terrain distance between two points in feet."
  [elevation-matrix cell-size [i1 j1] [i2 j2]]
  (let [di (* cell-size (- i1 i2))
        dj (* cell-size (- j1 j2))
        dz (- (m/mget elevation-matrix i1 j1)
              (m/mget elevation-matrix i2 j2))]
    (Math/sqrt (+ (* di di) (* dj dj) (* dz dz)))))

(def offset-to-degrees
  "Returns clockwise degrees from north."
  {[-1  0]   0.0   ; N
   [-1  1]  45.0   ; NE
   [ 0  1]  90.0   ; E
   [ 1  1] 135.0   ; SE
   [ 1  0] 180.0   ; S
   [ 1 -1] 225.0   ; SW
   [ 0 -1] 270.0   ; W
   [-1 -1] 315.0}) ; NW

(def rothermel-fast-wrapper
  (memoize
   (fn [fuel-model-number fuel-moisture]
     (let [fuel-model      (-> (build-fuel-model (int fuel-model-number))
                               (moisturize fuel-moisture))
           spread-info-min (rothermel-surface-fire-spread-no-wind-no-slope fuel-model)]
       [fuel-model spread-info-min]))))

(defn compute-burn-trajectory
  [neighbor here spread-info-min spread-info-max fuel-model crown-bulk-density
   canopy-cover canopy-height canopy-base-height foliar-moisture crown-spread-max
   crown-eccentricity landfire-rasters cell-size overflow-trajectory overflow-heat]
  (let [trajectory          (mop/- neighbor here)
        spread-direction    (offset-to-degrees trajectory)
        surface-spread-rate (rothermel-surface-fire-spread-any spread-info-max
                                                               spread-direction)
        residence-time      (:residence-time spread-info-min)
        reaction-intensity  (:reaction-intensity spread-info-min)
        surface-intensity   (->> (anderson-flame-depth surface-spread-rate residence-time)
                                 (byram-fire-line-intensity reaction-intensity))
        crown-fire?         (van-wagner-crown-fire-initiation? canopy-cover
                                                               canopy-base-height
                                                               foliar-moisture
                                                               surface-intensity)
        crown-spread-rate   (if crown-fire?
                              (rothermel-surface-fire-spread-any
                               (assoc spread-info-max
                                      :max-spread-rate crown-spread-max
                                      :eccentricity crown-eccentricity)
                               spread-direction))
        crown-intensity     (if crown-fire?
                              (crown-fire-line-intensity
                               crown-spread-rate
                               crown-bulk-density
                               canopy-height
                               canopy-base-height
                               (-> fuel-model :h :dead :1hr)))
        spread-rate         (if crown-fire?
                              (max surface-spread-rate crown-spread-rate)
                              surface-spread-rate)
        fire-line-intensity (if crown-fire?
                              (+ surface-intensity crown-intensity)
                              surface-intensity)
        flame-length        (byram-flame-length fire-line-intensity)]
    {:cell                neighbor
     :trajectory          trajectory
     :terrain-distance    (distance-3d (:elevation landfire-rasters) cell-size here neighbor)
     :spread-rate         spread-rate
     :fire-line-intensity fire-line-intensity
     :flame-length        flame-length
     :fractional-distance (volatile! (if (= trajectory overflow-trajectory)
                                       overflow-heat
                                       0.0))}))

(defn compute-neighborhood-fire-spread-rates!
  "Returns a vector of entries of the form:
  {:cell [i j],
   :trajectory [di dj],
   :terrain-distance ft,
   :spread-rate ft/min,
   :fire-line-intensity Btu/ft/s,
   :flame-length ft,
   :fractional-distance [0-1]}, one for each cell adjacent to here."
  [{:keys [landfire-rasters
           wind-speed-20ft
           wind-from-direction
           fuel-moisture
           foliar-moisture
           ellipse-adjustment-factor
           cell-size
           num-rows
           num-cols]}
   fire-spread-matrix
   [i j :as here]
   overflow-trajectory
   overflow-heat]
  (let [fuel-model-number   (m/mget (:fuel-model         landfire-rasters) i j)
        slope               (m/mget (:slope              landfire-rasters) i j)
        aspect              (m/mget (:aspect             landfire-rasters) i j)
        canopy-height       (m/mget (:canopy-height      landfire-rasters) i j)
        canopy-base-height  (m/mget (:canopy-base-height landfire-rasters) i j)
        crown-bulk-density  (m/mget (:crown-bulk-density landfire-rasters) i j)
        canopy-cover        (m/mget (:canopy-cover       landfire-rasters) i j)
        [fuel-model spread-info-min] (rothermel-fast-wrapper fuel-model-number fuel-moisture)
        midflame-wind-speed (* wind-speed-20ft 88.0
                               (wind-adjustment-factor (:delta fuel-model)
                                                       canopy-height
                                                       canopy-cover)) ; mi/hr -> ft/min
        spread-info-max     (rothermel-surface-fire-spread-max
                             spread-info-min midflame-wind-speed wind-from-direction
                             slope aspect ellipse-adjustment-factor)
        crown-spread-max    (cruz-crown-fire-spread wind-speed-20ft crown-bulk-density
                                                    (-> fuel-moisture :dead :1hr))
        crown-eccentricity  (crown-fire-eccentricity wind-speed-20ft
                                                     ellipse-adjustment-factor)]
    (into []
          (comp
           (filter #(and (in-bounds? num-rows num-cols %)
                         (burnable? fire-spread-matrix (:fuel-model landfire-rasters) %)))
           (map #(compute-burn-trajectory % here spread-info-min spread-info-max fuel-model
                                          crown-bulk-density canopy-cover canopy-height
                                          canopy-base-height foliar-moisture crown-spread-max
                                          crown-eccentricity landfire-rasters cell-size
                                          overflow-trajectory overflow-heat)))
          (get-neighbors here))))

(defn burnable-neighbors?
  [fire-spread-matrix fuel-model-matrix num-rows num-cols cell]
  (some #(and (in-bounds? num-rows num-cols %)
              (burnable? fire-spread-matrix fuel-model-matrix %))
        (get-neighbors cell)))

(defn select-random-ignition-site
  [fuel-model-matrix]
  (let [num-rows           (m/row-count    fuel-model-matrix)
        num-cols           (m/column-count fuel-model-matrix)
        fire-spread-matrix (m/zero-matrix num-rows num-cols)]
    (loop [[i j :as ignition-site] (random-cell num-rows num-cols)]
      (if (and (burnable-fuel-model? (m/mget fuel-model-matrix i j))
               (burnable-neighbors? fire-spread-matrix fuel-model-matrix
                                    num-rows num-cols ignition-site))
        ignition-site
        (recur (random-cell num-rows num-cols))))))

(defn identify-ignition-events
  [ignited-cells timestep]
  (->> (for [[_ destinations] ignited-cells
             {:keys [cell trajectory terrain-distance spread-rate flame-length
                     fire-line-intensity fractional-distance]} destinations]
         (let [new-spread-fraction (/ (* spread-rate timestep) terrain-distance)
               new-total           (vreset! fractional-distance
                                            (+ @fractional-distance new-spread-fraction))]
           (if (>= new-total 1.0)
             {:cell cell :trajectory trajectory :fractional-distance @fractional-distance
              :flame-length flame-length :fire-line-intensity fire-line-intensity})))
       (remove nil?)
       (group-by :cell)
       (map (fn [[_ trajectories]] (apply max-key :fractional-distance trajectories)))
       (into [])))

(defn update-ignited-cells
  [{:keys [landfire-rasters num-rows num-cols] :as constants}
   ignited-cells
   ignition-events
   fire-spread-matrix]
  (let [newly-ignited-cells (into #{} (map :cell) ignition-events)
        fuel-model-matrix   (:fuel-model landfire-rasters)]
    (into {}
          (concat
           (for [[cell spread-info] ignited-cells
                 :when (burnable-neighbors? fire-spread-matrix fuel-model-matrix
                                            num-rows num-cols cell)]
             [cell (remove #(contains? newly-ignited-cells (:cell %)) spread-info)])
           (for [{:keys [cell trajectory fractional-distance]} ignition-events
                 :when (burnable-neighbors? fire-spread-matrix fuel-model-matrix
                                            num-rows num-cols cell)]
             [cell (compute-neighborhood-fire-spread-rates!
                    constants
                    fire-spread-matrix
                    cell
                    trajectory
                    (- fractional-distance 1.0))])))))

(defn run-loop
  [{:keys [max-runtime cell-size] :as constants}
   ignited-cells
   fire-spread-matrix
   flame-length-matrix
   fire-line-intensity-matrix]
  (loop [global-clock  0.0
         ignited-cells ignited-cells]
    (if (and (< global-clock max-runtime)
             (seq ignited-cells))
      (let [dt              (->> ignited-cells
                                 (vals)
                                 (apply concat)
                                 (map :spread-rate)
                                 (reduce max 0.0)
                                 (/ cell-size))
            timestep        (if (> (+ global-clock dt) max-runtime)
                              (- max-runtime global-clock)
                              dt)
            ignition-events (identify-ignition-events ignited-cells timestep)]
        ;; [{:cell :trajectory :fractional-distance
        ;;   :flame-length :fire-line-intensity} ...]
        (doseq [{:keys [cell flame-length fire-line-intensity]} ignition-events]
          (let [[i j] cell]
            (m/mset! fire-spread-matrix         i j 1.0)
            (m/mset! flame-length-matrix        i j flame-length)
            (m/mset! fire-line-intensity-matrix i j fire-line-intensity)))
        (recur (+ global-clock timestep)
               (update-ignited-cells constants ignited-cells ignition-events fire-spread-matrix)))
      {:exit-condition             (if (seq ignited-cells) :max-runtime-reached :no-burnable-fuels)
       :fire-spread-matrix         fire-spread-matrix
       :flame-length-matrix        flame-length-matrix
       :fire-line-intensity-matrix fire-line-intensity-matrix})))

(defn- initialize-matrix
  [num-rows num-cols indices]
  (let [matrix (m/zero-matrix num-rows num-cols)]
    (doseq [[i j] indices
            :when (in-bounds? num-rows num-cols [i j])]
      (m/mset! matrix i j -1.0))
    matrix))

(defn- get-non-zero-indices [m]
  (for [[r cols] (map-indexed vector (m/non-zero-indices m))
        c        cols]
    [r c]))

(defmulti run-fire-spread
  "Runs the raster-based fire spread model with a map of these arguments:
  - max-runtime: double (minutes)
  - cell-size: double (feet)
  - landfire-rasters: map containing these entries;
    - elevation: core.matrix 2D double array (feet)
    - slope: core.matrix 2D double array (vertical feet/horizontal feet)
    - aspect: core.matrix 2D double array (degrees clockwise from north)
    - fuel-model: core.matrix 2D double array (fuel model numbers 1-256)
    - canopy-height: core.matrix 2D double array (feet)
    - canopy-base-height: core.matrix 2D double array (feet)
    - crown-bulk-density: core.matrix 2D double array (lb/ft^3)
    - canopy-cover: core.matrix 2D double array (0-100)
  - wind-speed-20ft: double (miles/hour)
  - wind-from-direction: double (degrees clockwise from north)
  - fuel-moisture: doubles (%){:dead {:1hr :10hr :100hr} :live {:herbaceous :woody}}
  - foliar-moisture: double (%)
  - ellipse-adjustment-factor: (< 1.0 = more circular, > 1.0 = more elliptical)
  - initial-ignition-site: One of the following:
     - point represented as [row col]
     - map containing a :matrix field of type core.matrix 2D double array (0-2)
     - nil (this causes GridFire to select a random ignition-point)
  - num-rows: integer
  - num-cols: integer"
  (fn [{:keys [initial-ignition-site]}]
    (condp = (type initial-ignition-site)
      clojure.lang.PersistentHashMap :ignition-perimeter
      clojure.lang.PersistentVector  :ignition-point
      :random-ignition-point)))

(defmethod run-fire-spread :random-ignition-point
  [{:keys [landfire-rasters] :as constants}]
  (run-fire-spread (assoc constants
                          :initial-ignition-site
                          (select-random-ignition-site (:fuel-model landfire-rasters)))))

(defmethod run-fire-spread :ignition-point
  [{:keys [landfire-rasters num-rows num-cols initial-ignition-site] :as constants}]
  (let [[i j]                      initial-ignition-site
        fuel-model-matrix          (:fuel-model landfire-rasters)
        fire-spread-matrix         (m/zero-matrix num-rows num-cols)
        flame-length-matrix        (m/zero-matrix num-rows num-cols)
        fire-line-intensity-matrix (m/zero-matrix num-rows num-cols)]
    (when (and (in-bounds? num-rows num-cols initial-ignition-site)
               (burnable-fuel-model? (m/mget fuel-model-matrix i j))
               (burnable-neighbors? fire-spread-matrix fuel-model-matrix
                                    num-rows num-cols initial-ignition-site))
      ;; initialize the ignition site
      (m/mset! fire-spread-matrix i j 1.0)
      (m/mset! flame-length-matrix i j 1.0)
      (m/mset! fire-line-intensity-matrix i j 1.0)
      (let [ignited-cells {initial-ignition-site
                           (compute-neighborhood-fire-spread-rates!
                            constants
                            fire-spread-matrix
                            initial-ignition-site
                            nil
                            0.0)}]
        (run-loop constants
                  ignited-cells
                  fire-spread-matrix
                  flame-length-matrix
                  fire-line-intensity-matrix)))))

(defmethod run-fire-spread :ignition-perimeter
  [{:keys [num-rows num-cols initial-ignition-site landfire-rasters] :as constants}]
  (let [fire-spread-matrix         (first (:matrix initial-ignition-site))
        non-zero-indices           (get-non-zero-indices fire-spread-matrix)
        flame-length-matrix        (initialize-matrix num-rows num-cols non-zero-indices)
        fire-line-intensity-matrix (initialize-matrix num-rows num-cols non-zero-indices)
        perimeter-indices          (filter #(burnable-neighbors? fire-spread-matrix
                                                                 (:fuel-model landfire-rasters)
                                                                 num-rows
                                                                 num-cols
                                                                 %)
                                           non-zero-indices)
        ignited-cells              (into {}
                                         (for [index perimeter-indices
                                               :let  [ignition-trajectories
                                                      (compute-neighborhood-fire-spread-rates!
                                                       constants
                                                       fire-spread-matrix
                                                       index
                                                       nil
                                                       0.0)]]
                                           [index ignition-trajectories]))]
    (run-loop constants
              ignited-cells
              fire-spread-matrix
              flame-length-matrix
              fire-line-intensity-matrix)))
;; fire-spread-algorithm ends here
