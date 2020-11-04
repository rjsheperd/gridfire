(ns gridfire.config
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [gridfire.crown-fire :refer [m->ft]]))

(defn file-path [directory file-name]
  (str directory "/" file-name ".tif"))

(defn convert [s]
  (cond
    (re-matches #"^-?[0-9]\d*\.(\d+)?$" s) (Float/parseFloat s)
    (re-matches #"^\d+$" s)                (Integer/parseInt s)
    (re-matches #".TRUE." s)               true
    (re-matches #".FALSE." s)              false
    :else                                  (subs s 1 (dec (count s)))))

(defn parse [s]
  (->> (str/split s #"\n")
       (filter #(str/includes? % "="))
       (mapcat #(str/split % #"="))
       (map str/trim)
       (apply hash-map)
       (reduce-kv (fn [m k v]
                    (assoc m k (convert v)))
                  {})))

(defn process-landfire-layers
  [{:strs [ASP_FILENAME CBH_FILENAME CC_FILENAME CH_FILENAME CBD_FILENAME
           FBFM_FILENAME SLP_FILENAME DEM_FILENAME FUELS_AND_TOPOGRAPHY_DIRECTORY]}
   _
   config]
  (let [dir FUELS_AND_TOPOGRAPHY_DIRECTORY]
    (merge config
           {:fetch-layer-method :geotiff
            :landfire-layers    {:aspect             (file-path dir ASP_FILENAME)
                                 :canopy-base-height (file-path dir CBH_FILENAME)
                                 :canopy-cover       (file-path dir CC_FILENAME)
                                 :canopy-height      (file-path dir CH_FILENAME)
                                 :crown-bulk-density (file-path dir CBD_FILENAME)
                                 :elevation          (file-path dir DEM_FILENAME)
                                 :fuel-model         (file-path dir FBFM_FILENAME)
                                 :slope              (file-path dir SLP_FILENAME)}})))

(defn process-ignition
  [{:strs [NUM_IGNITIONS PHI_FILENAME FUELS_AND_TOPOGRAPHY_DIRECTORY]}
   _
   config]
  (let [dir FUELS_AND_TOPOGRAPHY_DIRECTORY]
    (merge config
           {:fetch-ignition-method :geotiff
            :ignition-layer        (file-path dir PHI_FILENAME)})))

(defn process-weather
  [{:strs [STOCHASTIC_TMP_FILENAME STOCHASTIC_RH_FILENAME STOCHASTIC_WS_FILENAME
           STOCHASTIC_WD_FILENAME FOLIAR_MOISTURE_CONTENT FUELS_AND_TOPOGRAPHY_DIRECTORY]}
   _
   config]
  (let [dir FUELS_AND_TOPOGRAPHY_DIRECTORY]
    (merge config
           {:temperature         (file-path dir STOCHASTIC_TMP_FILENAME)
            :relative-humidity   (file-path dir STOCHASTIC_RH_FILENAME)
            :wind-speed-20ft     (file-path dir STOCHASTIC_WS_FILENAME)
            :wind-from-direction (file-path dir STOCHASTIC_WD_FILENAME)
            :foliar-moisture     FOLIAR_MOISTURE_CONTENT})))

(defn process-output
  [data {:keys [verbose]} config]
  (merge config
         {:outfile-suffix          ""
          :output-landfire-inputs? false
          :output-geotiffs?        false
          :output-pngs?            (if verbose true false)
          :output-csvs?            (if verbose true false)}))

(defn sec->min
  [seconds]
  (int (/ seconds 60)))

(defn build-edn
  [{:strs [COMPUTATIONAL_DOMAIN_CELLSIZE MAX_RUNTIME NUM_ENSEMBLE_MEMBERS SEED] :as data}
   options]
  (->> {:cell-size                 (m->ft COMPUTATIONAL_DOMAIN_CELLSIZE)
        :max-runtime               (sec->min MAX_RUNTIME)
        :simulations               NUM_ENSEMBLE_MEMBERS
        :random-seed               SEED
        :ellipse-adjustment-factor 1.0}
       (process-landfire-layers data options)
       (process-ignition data options)
       (process-weather data options)
       (process-output data options)))

(defn write-config [config-params]
  (let [file "gridfire.edn"]
    (println "Config file:" file)
    (spit file (with-out-str (pprint/pprint config-params)))))

(defn process-options
  [{:keys [config-file verbose] :as options}]
  (let [data (parse (slurp config-file))]
    (build-edn data options)))

(def cli-options
  [["-c" "--config-file FILE" "Path to an data file containing a map of simulation configs"]
   ["-v" "--verbose" "Flag for controlling outputs"]])

(defn -main
  [& args]
  (println (str "Converting configuration file to one that Gridfire accepts."))
  (let [{:keys [options arguments summary errors]} (parse-opts args cli-options)]
    (if (or (seq errors) (empty? options))
      (do
        (when (seq errors)
          (run! println errors)
          (newline))
        (println (str "Usage:\n" summary)))
      (write-config (process-options options)))))