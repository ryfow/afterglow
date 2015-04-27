(ns afterglow.effects.dimmer
  (:require [afterglow.channels :as channels]
            [afterglow.rhythm :refer [metro-beat-phase]]
            [taoensso.timbre :as timbre :refer [error warn info debug]]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile]]))

(defn- assign-level
  "Assigns a dimmer level to the channel."
  [level channel]
  (assoc channel :value level))

(defn dimmer-cue
  "Returns an effect function which simply assigns a fixed value to the fixtures supplied when invoked."
  [level fixtures]
  (let [assigned (map (partial assign-level level) (channels/extract-channels fixtures #(= (:type %) :dimmer)))
        result (map #(select-keys % [:address :universe :value]) assigned)]
    ;; TODO handle fixtures with fine dimmer channels through fractional values? Have extract-channels
    ;; synthesize extra :dimmer-fine channels, or change the way I define fixtures to just include those?
    ;; I am leaning towards the latter
    (fn [show snapshot]
      (pspy :dimmer-cue
            result))))

(defn sawtooth-beat
  "Returns an effect function which ramps the dimmer over each beat of a metronome."
  ([fixtures]
   (sawtooth-beat 0 255 false fixtures))
  ([min max down? fixtures]
   (let [range (- max min)
         chans (map #(select-keys % [:address :universe]) (channels/extract-channels fixtures #(= (:type %) :dimmer)))]
     (fn [show snapshot]
       (pspy :sawtooth-beat
          (let [phase (.beat-phase snapshot)
                adj (if down? (- 1.0 phase) phase)
                new-level (+ min (int (* range adj)))]
            (map (partial assign-level new-level) chans)))))))
