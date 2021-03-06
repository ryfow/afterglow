(ns afterglow.show
  "Encapsulates a synchronized light show, executing a varying
  collection of effects with output to a number of DMX universes.
  Assumes control of the assigned universes, so only one show at a
  time should be assigned a given universe. Of course, you can stack
  as many effects as you'd like in that show.

  The effects are maintained in a priority queue, with higher-priority
  effects running after lower-priority ones, so they can adjust or
  simply replace the channel assignments established by earlier
  effects. Some may choose to implement traditional channel-oriented
  highest-takes-prority or latest-takes-priority semantics, others do
  more sophisticated color blending or position averageing. The
  default priority when adding an effect is zero, but you can assign
  it any integer, and it will be inserted into the queue after any
  existing effects with the same priority.

  All effects are assigned a keyword when they are added, and adding a
  new effect with the same key as an existing effect will replace the
  former one."
  {:author "James Elliott"
   :doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.controllers :as controllers]
            [afterglow.effects :as fx]
            [afterglow.effects.channel :refer [channel-assignment-resolver
                                               function-assignment-resolver]]
            [afterglow.effects.color :refer [color-assignment-resolver]]
            [afterglow.effects.dimmer :refer [master master-set-level]]
            [afterglow.effects.movement :refer [direction-assignment-resolver
                                                aim-assignment-resolver]]
            [afterglow.effects.params :refer [bind-keyword-param resolve-param]]
            [afterglow.fixtures :as fixtures]
            [afterglow.midi :as midi]
            [afterglow.ola-service :as ola]
            [afterglow.rhythm :refer :all]
            [afterglow.show-context :refer [*show* with-show]]
            [afterglow.transform :as transform]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [com.climate.claypoole :as cp]
            [environ.core :refer [env]]
            [overtone.at-at :as at-at]
            [overtone.midi]
            [taoensso.timbre :refer [error spy]]
            [taoensso.timbre.profiling :refer [p profile pspy]])
  (:import (afterglow.effects.dimmer Master)
           (afterglow.effects Assigner Effect)
           (afterglow.rhythm Metronome)
           (com.google.protobuf ByteString)))

(defonce ^{:doc "How often should frames of DMX data be sent out; this
  should be a supported frame rate for your interface. The default
  here is 30 Hz, thirty frames per second."}
  default-refresh-interval
  (/ 1000 30))

(defonce ^{:doc "Provides thread scheduling for all shows' DMX data generation."
           :private true}
  scheduler
  (at-at/mk-pool))

(def resolution-handlers
  "The order in which assigners should be evaluated, and the functions
  which resolve them into DMX channel updates. A list of tuples which
  identify the assigner type key, and the function to invoke for such
  assigners."
  [[:channel channel-assignment-resolver]
   [:function function-assignment-resolver]
   [:color color-assignment-resolver]
   [:direction direction-assignment-resolver]
   [:aim aim-assignment-resolver]])

(defn- gather-assigners
  "Collect all of the assigners that are in effect at the current
  moment in the show, organized by type and the unique ID of the
  element they affect, sorted in priority order under those keys."
  [show snapshot]
  (pspy :gather-assigners
        (reduce (fn [altered-map assigner]
                  (let [key-path [(:kind assigner) (:target-id assigner)]]
                    (assoc-in altered-map key-path (conj (get-in altered-map key-path []) assigner))))
                {}
                (apply concat (cp/pmap @(:pool show) #(fx/generate % show snapshot)
                                       (:effects @(:active-effects show)))))))

(defn- run-assigners
  "Returns a tuple of the target to be assigned, the target ID of the
  assigner, and the final value for that target, after iterating over
  an assigner list."
  [show snapshot assigners]
  (pspy :run-assigners
        (reduce (fn [result-so-far assigner]
                  [(:target assigner) (:target-id assigner)
                   (fx/assign assigner show snapshot (:target assigner)
                              (get result-so-far 2))])
                []
                assigners)))

(declare end-effect!)

(def ^:private frame-count-for-load
  "The number of frames to keep track of for calculating the current
  load on the show."
  30)

(defn- update-stats
  "Update the count of how many frames have been sent, total and
  average time computing them, and warn if the most recent one took
  longer than the frame interval."
  [stats began refresh-interval]
  (let [duration (- (at-at/now) began)
        total-time (+ duration (:total-time stats 0))
        frames-sent (inc (:frames-sent stats 0))
        average-duration (float (/ total-time frames-sent))
        recent (:recent stats (ring-buffer 30))
        discarding (if (< (count recent) frame-count-for-load) 0 (- (peek recent)))
        recent (conj recent duration)
        recent-total (+ (:recent-total stats 0) duration discarding)
        recent-average (float (/ recent-total (count recent)))]
    (when (> duration refresh-interval)
      (taoensso.timbre/warn "Frame took" duration "ms to generate, refresh interval is" refresh-interval "ms."))
    (assoc stats :total-time total-time :frames-sent frames-sent :average-duration average-duration
           :recent recent :recent-total recent-total :recent-average recent-average)))

(defn current-load
  "Returns a sense of how much headroom there is running the current
  effects, in the form of the fraction of the available refresh
  interval that was used calculating and sending the last several
  frames."
  {:pre [(some? *show*)]}
  []
  (/ (:recent-average @(:statistics *show*) 0) (:refresh-interval *show*)))

(defn- send-dmx
  "Calculate and send the next frame of DMX values for the universes
  and effects run by this show, as described in
  [The Rendering Loop](https://github.com/brunchboy/afterglow/blob/master/doc/rendering_loop.adoc#the-rendering-loop)."
  {:doc/format :markdown}
  [show buffers]
  (try
    (let [began (at-at/now)
          snapshot (metro-snapshot (:metronome show))]
      (p :clear-buffers (doseq [levels (vals buffers)] (java.util.Arrays/fill levels (byte 0))))
      (p :clean-finished-effects (let [indexed (cp/pmap @(:pool show) vector (iterate inc 0)
                                                        (:effects @(:active-effects show)))]
                                   (doseq [[index effect] indexed]
                                     (when-not (fx/still-active? effect show snapshot)
                                       (end-effect! (:key (get (:meta @(:active-effects show)) index)) :force true)))))
      (let [all-assigners (gather-assigners show snapshot)]
        (doseq [[kind handler] resolution-handlers]
          (doseq [assigners (vals (get all-assigners kind))]
            (let [[target target-id value] (run-assigners show snapshot assigners)]
              (when (some? value) ; If the assigner returned nil, it wants to be skipped
                (p :resolve-value (handler show buffers snapshot target value target-id)))))))
      (p :send-dmx-data (doseq [universe (keys buffers)]
                          (let [levels (get buffers universe)]
                            (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil))))
      (swap! (:movement *show*) #(dissoc (assoc % :previous (:current %)) :current))
      (swap! (:statistics *show*) update-stats began (:refresh-interval show)))
    (catch Throwable t
      (error t "Problem trying to run cues"))))

(defn stop!
  "Shuts down and removes the scheduled task which is sending DMX
  values for [[*show*]], and cleans up the show's thread pool."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (swap! (:task *show*) #(do (when % (at-at/stop %)) nil))
  (swap! (:pool *show*) #(do (when % (cp/shutdown %)) nil))
  @(:statistics *show*))

(defn- create-buffers
  "Create the map of universe IDs to byte arrays used to calculate DMX
  values for universes managed by the specified show."
  [show]
  (into {} (for [universe (:universes show)] [universe (byte-array 512)])))

(defn running?
  "Returns an indication of whether the show is currently generating and
  sending values to its associated lighting universes."
  []
  {:pre [(some? *show*)]}
  (some? @(:task *show*)))

(defn start!
  "Starts (or restarts) a scheduled task to calculate and send DMX
  values to the universes controlled by [[*show*]] at the
  appropriate refresh rate."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (stop!)
  (let [buffers (create-buffers *show*)]
    (swap! (:pool *show*) #(or % (cp/threadpool (cp/ncpus) :name (str "show-" (:id *show*)))))
    (swap! (:task *show*) #(do (when % (at-at/stop %))
                             (at-at/every (:refresh-interval *show*)
                                          (fn [] (send-dmx *show* buffers))
                                          scheduler))))
  nil)

(defonce ^{:doc "Used to give each show a unique ID, for registering
  its MIDI event handlers, etc."
           :private true}
  show-counter
  (atom 0))

;; TODO: Should some of these atoms be refs and use dosync?
(defn show
  "Create a show coordinator to calculate and send DMX values to the
  specified universe(s), with a [[rhythm/metronome]] to coordinate
  timing. Values are computed and sent at the specified refresh
  interval (in milliseconds), which defaults to a frame rate of thirty
  times per second."
  {:doc/format :markdown}
  [& {:keys [universes base-metronome refresh-interval]
      :or {universes [1] base-metronome (metronome 120) refresh-interval default-refresh-interval}}]
  {:pre [(sequential? universes) (pos? (count universes)) (every? integer? universes) (not-any? neg? universes)
         (satisfies? IMetronome base-metronome) (number? refresh-interval) (pos? refresh-interval)]}
  {:id (swap! show-counter inc)
   :metronome base-metronome
   :sync (atom nil)
   :refresh-interval refresh-interval
   :universes (set universes)
   :next-id (atom 0)
   :active-effects (atom {:effects []
                          :indices {}
                          :meta []
                          :ending #{}})
   :variables (atom {})
   :grand-master (master nil)  ; Only the grand master can have no show, or parent.
   :fixtures (atom {})
   :movement (atom {})  ; Used to smooth head motion between frames
   :statistics (atom { :afterglow-version (env :afterglow-version)})
   :dimensions (atom {})
   :grid-controllers (atom #{})
   :task (atom nil)
   :pool (atom nil)
   :cue-grid (controllers/cue-grid)})

(defn stop-all!
  "Kills all scheduled tasks which shows may have created to output
  their DMX values. You should still call stop! on each show you have
  started in order to clean up that show's thread pool. But this can
  quickly stop a runaway train if you don't know which show is to
  blame."
  []
  (at-at/stop-and-reset-pool! scheduler))

(defn sync-to-external-clock
  "Stops or sarts synchronizing the show metronome attached
  to [[*show*]] with an external clock source. Pass it a function
  which takes the metronome and binds it to the source, returning a
  ClockSync object which will be stored with the show, so
  synchronization can later be stopped if desired. Calling this stops
  any synchronization that was formerly in effect, and calling it with
  no sync-fn argument simply leaves it stopped.

  Functions useful to pass to this include [[midi/sync-to-midi-clock]]
  and [[dj-link/sync-to-dj-link]]."
  {:doc/format :markdown}
  ([]
   (sync-to-external-clock nil))
  ([sync-fn]
   {:pre [(some? *show*)]}
   (swap! (:sync *show*) (fn [former-sync]
                           (when former-sync (midi/sync-stop former-sync))
                           (when sync-fn (sync-fn (:metronome *show*)))))))

(defn sync-status
  "Checks what kind of synchronization is in effect for [[*show*]],
  and reports on how it seems to be working."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (if-let [sync-in-effect @(:sync *show*)]
    (midi/sync-status sync-in-effect)
    {:type :manual}))

;; TODO: Someday generate feedback on assigned MIDI channels, etc...
(defn set-variable!
  "Set a value for a variable associated with [[*show*]]."
  {:doc/format :markdown}
  [key newval]
  {:pre [(some? *show*) (some? key)]}
  (swap! (:variables *show*) #(if (some? newval)
                                (assoc % (keyword key) newval)
                                (dissoc % (keyword key)))))

(defn get-variable
  "Get the value of a variable associated with [[*show]]."
  {:doc/format :markdown}
  [key]
  {:pre [(some? key)]}
  ((keyword key) @(:variables *show*)))

(defn add-midi-control-to-var-mapping
  "Cause the specified variable in [[*show*]] to be updated by any
  MIDI controller-change messages from the specified device sent on
  the specified channel and controller number. If `:min` and/or `:max`
  are specified, the normal MIDI range from 0 to 127 will be mapped to
  the supplied range instead." {:doc/format :markdown}
  [midi-device-name channel control-number variable & {:keys [min max] :or {min 0 max 127}}]
  {:pre [(some? *show*) (some? midi-device-name) (number? min) (number? max) (not= min max)
         (integer? channel) (<= 0 channel 15) (integer? control-number) (<= 0 control-number 127) (some? variable)]}
  (let [show *show*  ; Bind so we can pass it to update function running on another thread
        calc-fn (cond (and (zero? min) (= max 127))
                      (fn [midi-val] midi-val)
                      (< min max)
                      (let [range (- max min)]
                        (fn [midi-val] (float (+ min (/ (* midi-val range) 127)))))
                      :else
                      (let [range (- min max)]
                        (fn [midi-val] (float (+ max (/ (* midi-val range) 127))))))]
    (midi/add-control-mapping midi-device-name channel control-number (str "show:" (:id show) ":var" (keyword variable))
                              (fn [msg] (with-show show
                                          (set-variable! variable (calc-fn (:velocity msg))))))))

(defn remove-midi-control-to-var-mapping
  "Cease updating the specified variable in [[*show*]] when the
  specified MIDI controller-change messages are received."
  {:doc/format :markdown}
  [midi-device-name channel control-number variable]
  {:pre [(some? *show*) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? control-number) (<= 0 control-number 127) (some? variable)]}
  (midi/remove-control-mapping midi-device-name channel control-number (str "show:" (:id *show*)
                                                                            ":var" (keyword variable))))

(defn add-midi-control-to-master-mapping
  "Cause the specified [[dimmer/master]] in [[*show*]] to be updated
  by any MIDI controller-change messages from the specified device
  sent on the specified channel and controller number. If `:min`
  and/or `:max` are specified, they will be used instead of the normal
  master range of 0 to 100. If given, both `:min` and `:max` must be
  valid percentages (in the range 0 to 100). If no `:master` is
  supplied, the show's grand master is mapped. If the value supplied
  with `:master` is a keyword, it is resolved as a show variable
  containing a dimmer master."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [master min max] :or {master (:grand-master *show*) min 0 max 100}}]
  {:pre [(some? *show*) (some? midi-device-name) (number? min) (number? max) (not= min max)
         (<= 0 min 100) (<= 0 max 100)
         (integer? channel) (<= 0 channel 15) (integer? control-number) (<= 0 control-number 127)]}
  (let [bound (bind-keyword-param master Master (:grand-master *show*))
        master (resolve-param bound *show* (metro-snapshot (:metronome *show*)))
        calc-fn (if (< min max)
                  (let [range (- max min)]
                    (fn [midi-val] (float (+ min (/ (* midi-val range) 127)))))
                  (let [range (- min max)]
                    (fn [midi-val] (float (+ max (/ (* midi-val range) 127))))))]
    (midi/add-control-mapping midi-device-name channel control-number
                              (str "show:" (:id *show*) ":master" (.hashCode master))
                              (fn [msg] (master-set-level master (calc-fn (:velocity msg)))))))

(defn remove-midi-control-to-master-mapping
  "Cease updating the specified [[dimmer/master]] when the specified
  MIDI controller-change messages are received."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [master] :or {master (:grand-master *show*)}}]
  {:pre [(some? *show*) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? control-number) (<= 0 control-number 127)]}
  (let [bound (bind-keyword-param master Master (:grand-master *show*))
        master (resolve-param bound *show* (metro-snapshot (:metronome *show*)))]
    (midi/remove-control-mapping midi-device-name channel control-number (str "show:" (:id *show*)
                                                                              ":master" (.hashCode master)))))

(defn- add-midi-control-metronome-mapping
  "Helper function to perform some action on a metronome when a
  control-change message with non-zero value is received from the
  specified device, channel, and controller number."
  [midi-device-name channel control-number metronome mapped-fn]
  {:pre [(some? *show*) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? control-number) (<= 0 control-number 127) (ifn? mapped-fn)]}
  (let [bound (bind-keyword-param metronome Metronome (:metronome *show*))
        metronome (resolve-param bound *show* metro-snapshot (:metronome *show*))]
    (midi/add-control-mapping midi-device-name channel control-number
                              (str "show:" (:id *show*) ":metronome" (.hashCode metronome))
                              (fn [msg] (when (pos? (:velocity msg)) (mapped-fn metronome))))))

(defn add-midi-control-metronome-reset-mapping
  "Cause a metronome to be reset to beat 1, bar 1, phrase 1 when a
  control-change message with non-zero value is received from the
  specified device, channel, and controller number. If keyword
  parameter `:metronome` is supplied, its value can either be a
  [[rhythm/metronome]] object, or a keyword naming a show variable
  containing such an object. If not supplied, the main metronome
  attached to [[*show*]] is mapped."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [metronome] :or {metronome (:metronome *show*)}}]
  (add-midi-control-metronome-mapping midi-device-name channel control-number metronome
                                      #(metro-start % 1)))

(defn remove-midi-control-metronome-mapping
  "Stop affecting a metronome when the specified MIDI
  controller-change messages are received. This undoes the effect of
  any of [[add-midi-control-metronome-reset-mapping]],
  [[add-midi-control-metronome-align-bar-mapping]], and
  [[add-midi-control-metronome-align-phrase-mapping]]. If keyword
  parameter `:metronome` is supplied, its value can either be a
  [[rhythm/metronome]] object, or a keyword naming a show variable
  containing such an object. If not supplied, the main metronome
  attached to [[*show*]] is mapped."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [metronome] :or {metronome (:metronome *show*)}}]
  {:pre [(some? *show*) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? control-number) (<= 0 control-number 127)]}
  (let [bound (bind-keyword-param metronome Metronome (:metronome *show*))
        metronome (resolve-param bound *show* metro-snapshot (:metronome *show*))]
    (midi/remove-control-mapping midi-device-name channel control-number
                                 (str "show:" (:id *show*) ":metronome" (.hashCode metronome)))))

(defn add-midi-control-metronome-align-bar-mapping
  "Adjust a metronome so the closest beat is considered the first in
  the current measure, without moving the beat, when a control-change
  message with non-zero value is received from the specified device,
  channel, and controller number If keyword
  parameter `:metronome` is supplied, its value can either be a
  [[rhythm/metronome]] object, or a keyword naming a show variable containing
  such an object. If not supplied, the main metronome attached to [[*show*]]
  is unmapped."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [metronome] :or {metronome (:metronome *show*)}}]
  (add-midi-control-metronome-mapping midi-device-name channel control-number metronome
                                      #(metro-bar-start % (metro-bar %))))

(defn add-midi-control-metronome-align-phrase-mapping
  "Adjust a metronome so the closest beat is considered the first in
  the current phrase, without moving the beat, when a control-change
  message with non-zero value is received from the specified device,
  channel, and controller number. If keyword
  parameter `:metronome` is supplied, its value can either be a
  [[rhythm/metronome]] object, or a keyword naming a show variable
  containing such an object. If not supplied, the main metronome
  attached to [[*show*]] is mapped."
  {:doc/format :markdown}
  [midi-device-name channel control-number & {:keys [metronome] :or {metronome (:metronome *show*)}}]
  (add-midi-control-metronome-mapping midi-device-name channel control-number metronome
                                      #(metro-phrase-start % (metro-phrase %))))

(defn- vec-remove
  "Remove the element at the specified index from the collection."
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn- remove-key
  "Helper function which removes a key from the map of keys to indices, and decrements any
  indices whose value was equal to or greater than that of the key being removed."
  [m key index]
  (into {} (for [[k v] (dissoc m key)] [k (if (>= v index) (dec v) v)])))

(defn- remove-effect-internal
  "Helper function which removes the effect with the specified key
  from the priority list structure maintained for the show."
  [fns key]
  (if-let [index (get (:indices fns) key)]
    {:effects (vec-remove (:effects fns) index)
     :indices (remove-key (:indices fns) key index)
     :meta (vec-remove (:meta fns) index)
     :ending (disj (:ending fns) key)}
    fns))

(defn- find-insertion-index
  "Determines where in the priority list an effect with the specified
  priority should be inserted: Starting at the end, look backwards for
  a priority that is equal to or less than the value being inserted,
  since later effects take priority over earlier ones."
  [coll priority]
  (loop [pos (count coll)]
    (cond (zero? pos)
          pos
          (<= (:priority (get coll (dec pos))) priority)
          pos
          :else
          (recur (dec pos)))))

(defn- vec-insert
  "Helper function which inserts an item at a specified index in a vector."
  [coll pos item]
  (let [pieces (split-at pos coll)] 
    (apply conj (vec (first pieces)) item (vec (fnext pieces)))))

(defn- insert-key
  "Helper function which adds a new key to the map of keys to indices, and increments any
  indices whose value was equal to or greater than that of the key being inserted."
  [m key index]
  (assoc (into {} (for [[k v] m] [k (if (>= v index) (inc v) v)]))
         key index))

(defn- add-effect-internal
  "Helper function which adds an effect with a specified key and priority to the priority
  list structure maintained for the show, replacing any existing effect with the same key.
  Tracks the effect instance id, cue-grid source, and variable binding map as metadata."
  [fns key f priority id from-cue var-map]
  (let [base (remove-effect-internal fns key)
        index (find-insertion-index (:meta base) priority)]
    {:effects (vec-insert (:effects base) index f)
     :indices (insert-key (:indices base) key index)
     :meta (vec-insert (:meta base) index (merge {:key key :priority priority :id id :started (at-at/now)}
                                                 (when from-cue {:cue from-cue})
                                                 (when var-map {:variables var-map})))
     :ending (:ending base)}))

(defn add-effect!
  "Add an effect to the active set which are affecting DMX outputs
  for [[show-context/*show*]]. If no priority is specified, zero is
  used. This effect is added after all existing effects with equal or
  lower priority, and replaces any existing effect with the same key.
  Since the effects are executed in order, ones which come later will
  win when setting DMX values for the same channel if that channel
  uses latest-takes-priority mode; for channels using highest-takes
  priority, the order does not matter. Effects can also use more
  sophisticated strategies for adjusting the results of earlier
  effects, but the later one always gets to decide what to do.

  Returns the unique id assigned to this particular effect activation,
  so that user interfaces can detect whether it is still active.

  The `:from-cue` keyword argument is used to keep track of effects
  which were launched from the cue grid, to help provide feedback on
  control surfaces and in the web interface. `:var-map` is used to
  supply a map of variable bindings associated with the cue, also for
  use by interfaces which support them."
  {:doc/format :markdown}
  [key f & {:keys [priority from-cue var-map] :or {priority 0}}]
  {:pre [(some? *show*) (some? key) (instance? Effect f) (integer? priority)]}
  (let [id (swap! (:next-id *show*) inc)]
    (swap! (:active-effects *show*) #(add-effect-internal % (keyword key) f priority id from-cue var-map))
    id))

(defn- vec-remove
  "Remove the element at the specified index from the collection."
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn find-effect
  "Looks up the specified effect keyword in list of active effects
  for [[*show*]]. Returns a map of the effect metadata, with the
  effect itself under the key `:effect`."
  {:doc/format :markdown}
  [key]
  {:pre [(some? *show*) (some? key)]}
  (when-let [index (get (:indices @(:active-effects *show*)) (keyword key))]
    (assoc (get (:meta @(:active-effects *show*)) index)
           :effect (get (:effects @(:active-effects *show*)) index)
           :ending ((:ending @(:active-effects *show*)) (keyword key)))))

(defn end-effect!
  "Shut down an effect that is running in [[*show*]]. Unless a `true`
  value is passed for `:force`, this is done by asking the effect to
  end (and waiting until it reports completion); forcibly stopping it
  simply immediately removes it from the show. If an id is specified
  with `:when-id`, the effect will only be ended if the id of the
  currently-running effect matches the one supplied."
  {:doc/format :markdown}
  [key & {:keys [force when-id]}]
  {:pre [(some? *show*) (some? key)]}
  (let [found (find-effect key)
        effect (:effect found)
        key (keyword key)] 
    ;; Make sure the effect is actually running, and if the caller cares, is the right instance
    (when (and effect (or (nil? when-id) (= (:id found) when-id)))
      ;; See if it should be forcibly or gently ended
      (if (or force ((:ending @(:active-effects *show*)) key)
              (fx/end effect *show* (metro-snapshot (:metronome *show*))))
        (swap! (:active-effects *show*) #(remove-effect-internal % key))
        (swap! (:active-effects *show*) #(update-in % [:ending] conj key))))))

(defn clear-effects!
  "Remove all effects currently active in [[*show*]], leading to a
  blackout state in all controlled universes (if the show is running)
  until new effects are added."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (reset! (:active-effects *show*) {:effects [],
                                    :indices {},
                                    :meta [],
                                    :priorities []
                                    :ending #{}}))

;; TODO: Consider someday cleaning up these variables when the cue ends.
(defn- introduce-cue-temp-variables
  "Creates any temporary variable parameters specified by the cue
  variable list, and returns the var-map that the effect creation
  function will need to be able to find them in the show."
  [cue x y var-overrides]
  (reduce (fn [result v]
            (if (string? (:key v))
              (let [temp-var (keyword (str "cue-temp-" x "-" y "-" (:key v)))
                    initial-value (or ((keyword (:key v)) var-overrides) (:start v))]
                (when initial-value
                  (set-variable! temp-var initial-value))
                (assoc result (keyword (:key v)) temp-var))
              result)) {} (:variables cue)))

(defn add-effect-from-cue-grid!
  "Finds the cue, if any, at the specified grid coordinates, and
  activates its effect with the designated key and priority, after
  ending any effects whose keys are specified in the cue's
  `:end-keys`. Returns the id of the new effect, or nil if no cue was
  found.

  A map of variable keywords to values can be supplied with
  `:var-overrides`, and the corresponding value will be used rather
  than the initial value specified in the cue for that variable when
  it is introduced as a temporary cue variable. This is used by
  compound cues to launch their nested cues with customized values."
  {:doc/format :markdown}
  [x y & {:keys [var-overrides]}]
  {:pre [(some? *show*)]}
  (when-let [cue (controllers/cue-at (:cue-grid *show*) x y)]
    (doseq [k (:end-keys cue)]
      (end-effect! k))
    (let [var-map (introduce-cue-temp-variables cue x y var-overrides)
          id (add-effect! (:key cue) ((:effect cue) var-map)
                          :priority (:priority cue) :from-cue cue :var-map var-map)]
      (controllers/activate-cue! (:cue-grid *show*) x y id)
      id)))

(defn find-cue-grid-active-effect
  "Find the cue at a particular cue grid location. If it is marked as
  active, check whether there is still an effect running under that
  key with the same id. If so, return a vector containing both the cue
  and the effect. If not, mark the cue as inactive, and return a
  vector containing the cue and nil. If no cue was found at all,
  simply returns nil."
  [show x y]
  (when-let [cue (controllers/cue-at (:cue-grid show) x y)]
    (let [effect-found (find-effect (:key cue))
          active (when (:active-id cue)
                   (if (and effect-found (= (:id effect-found) (:active-id cue)))
                     effect-found
                     (do (controllers/activate-cue! (:cue-grid show) x y nil)
                         nil)))]
      [cue active])))

(defn add-midi-control-to-cue-mapping
  "Cause the specified cue from the [[*show*]] cue grid to be
  triggered by receipt of the specified note (when `kind` is `:note`)
  or controller-change (when `kind` is `:control`) message with a
  non-zero velocity or control value. This allows generic MIDI
  controllers, which do not have enough pads or feedback capabilities
  to act as a full grid controller like the Ableton Push, to still
  provide a physical means of triggering cues. The desired cue is
  identified by passing in its `x` and `y` coordinates within the show
  cue grid.

  Afterglow will attempt to provide feedback about the progress of the
  cue by sending note on/off or control-change values to the same
  controller when the cue starts and ends. The note velocities or
  control values used can be changed by passing in different values
  with `:feedback-on` and `:feedback-off`, and this behavior can be
  suppressed entirely by passing `false` with `:feedback-on`.

  Afterglow assumes the control is momentary, meaning it sends a note
  off (or control value of 0) as soon as it is released, and a second
  press will be used to end the cue unless the cue uses the `:held`
  modifier to indicate it should be ended when the button is released.
  If your controller does not have momentary buttons and already
  requires a second press to turn off the note or control value, pass
  `false` with `:momentary` and Afterglow will always end cues when it
  receives a control value of 0, even if cues are not marked as
  `:held`."
  {:doc/format :markdown}
  [midi-device-name channel kind note x y & {:keys [feedback-on feedback-off momentary]
                                                       :or {feedback-on 127 feedback-off 0 momentary true}}]
  {:pre [(some? *show*) (#{:control :note} kind) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? note) (<= 0 note 127) (integer? x) (<= 0 x) (integer? y) (<= 0 y)
         (or (not feedback-on) (and (integer? feedback-on) (<= 0 feedback-on 127)))
         (integer? feedback-off) (<= 0 feedback-off 127)]}
  (let [show *show*  ; Bind so we can pass it to update functions running on another thread
        feedback-device (when feedback-on (midi/find-midi-out midi-device-name))
        our-id (atom nil)  ; Track when we have created an effect
        midi-handler (fn [msg]
                       (with-show show
                         ;; See if the cue exists and is running
                         (let [[cue active] (find-cue-grid-active-effect show x y)]
                           (when cue
                             (if (and (pos? (:velocity msg)) (not= (:command msg) :note-off))
                               ;; Control or note has been pressed
                               (if active
                                 (end-effect! (:key cue))
                                 (do
                                   (reset! our-id (add-effect-from-cue-grid! x y))))
                               ;; Control has been released
                               (when (and (some? @our-id) (or (:held cue) (not momentary)))
                                 (end-effect! (:key cue) :when-id @our-id)
                                 (reset! our-id nil)))))))]
    (when feedback-device  ; Set up to give feedback as cue activation changes
      (controllers/add-cue-feedback! (:cue-grid show) x y feedback-device channel kind note
                                     :on feedback-on :off feedback-off)
      (let [[cue active] (find-cue-grid-active-effect show x y)]  ; Was already active, so reflect that
        (when active (case kind
                      :control (overtone.midi/midi-control feedback-device note feedback-on channel)
                      :note (overtone.midi/midi-note-on feedback-device note feedback-on channel)))))
    ;; TODO: Add aftertouch support
    (case kind
      :control (midi/add-control-mapping midi-device-name channel note (str "show:" (:id show) ":cue-control" x "," y)
                                         midi-handler)
      :note (midi/add-note-mapping midi-device-name channel note (str "show:" (:id show) ":cue-note" x "," y)
                                   midi-handler))))

(defn remove-midi-control-to-cue-mapping
  "Stop triggering the specified cue from the [[*show*]] cue grid upon
  receipt of the specified note or controller-change message. The
  desired cue is identified by passing in its `x` and `y` coordinates
  within the show cue grid."
 {:doc/format :markdown}
  [midi-device-name channel kind note x y]
  {:pre [(some? *show*) (#{:control :note} kind) (some? midi-device-name) (integer? channel) (<= 0 channel 15)
         (integer? note) (<= 0 note 127) (integer? x) (<= 0 x) (integer? y) (<= 0 y)]}
  (let [feedback-device (midi/find-midi-out midi-device-name)
        feedback (controllers/clear-cue-feedback! (:cue-grid *show*) x y feedback-device channel kind note)]
    (when feedback  ; We had been giving feedback, see if we need to turn it off
      (let [[cue active] (find-cue-grid-active-effect *show* x y)]
        (when active (case kind
                       :control (overtone.midi/midi-control feedback-device note (second feedback) channel)
                       :note (overtone.midi/midi-note-on feedback-device note (second feedback) channel))))))
  (case kind
    :control (midi/remove-control-mapping midi-device-name channel note
                                          (str "show:" (:id *show*) ":cue-control" x "," y))
    :note (midi/remove-note-mapping midi-device-name channel note (str "show:" (:id *show*) ":cue-note" x "," y))))

(defn- address-map-internal
  "Helper function which returns a sorted map whose keys are all
  addresses in use in a given universe within a fixture map, and whose
  values are the fixture key using that universe address."
  [fixtures universe]
  (reduce (fn [addr-map [k v]] (if (= universe (:universe (first (:channels v))))
                                 (into addr-map (for [address (chan/all-addresses [v])]
                                                  [address (or (:key v) (:key (:fixture v)))]))
                                 addr-map)) (sorted-map) fixtures))

(defn address-map
  "Returns a sorted map whose keys are the IDs of the universes
  managed by [[*show*]], and whose values are address maps for the
  corresponding universe. The address maps have keys for every channel
  in use by the show in that universe, and the value is the key of the
  fixture using that address."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (into (sorted-map) (for [u (:universes *show*)]
             [u (address-map-internal @(:fixtures *show*) u)])))

(defn remove-fixture!
  "Remove a fixture from thosed patched into [[*show*]]."
  {:doc/format :markdown}
  [key]
  {:pre [(some? *show*) (some? key)]}
  (swap! (:fixtures *show*) #(dissoc % (keyword key)))
  nil)

(defn- next-id
  "Assign an ID value which is unique to the current show, for
  efficient identification of fixtures and heads while combining
  effects functions."
  []
  {:pre [(some? *show*)]}
  (swap! (:next-id *show*) inc))

(defn- patch-fixture-internal
  "Helper function which patches a fixture to a given address and universe, first removing
  any fixture which was previously assigned that key, and making sure there are no DMX
  channel collisions."
  [show fixtures ^clojure.lang.Keyword key fixture]
  (let [base (dissoc fixtures key)
        addrs-in-use (address-map-internal base (:universe (first (:channels fixture))))
        addrs-wanted (chan/all-addresses [fixture])
        max-needed (apply max addrs-wanted)
        conflicts (select-keys addrs-in-use addrs-wanted)]
    (when (> max-needed 512)
      (throw (IllegalArgumentException. (str "Cannot complete patch: Would use addresses up to " max-needed
                                             " which exceeds the DMX upper bound of 512."))))
    (when (seq conflicts)
      (throw (IllegalStateException.
              (str "Cannot complete patch: "
                   (clojure.string/join ", " (vec (for [[k v] conflicts]
                                                    (str "Channel " k " in use by fixture " v))))))))
    (assoc fixtures
           key (assoc (fixtures/index-color-wheel-hues
                       (fixtures/index-functions fixture)) :key key :id (next-id)))))

(defn- calculate-dimensions
  "Determine the center and bounding cube of the show, as defined by
  the minimum and maximum coordinates in all three dimensions of all
  patched fixtures and heads. Also enumerate the fixtures which might
  and heads which might participate in a visualization, in a map whose
  keys are their sorted IDs."
  [show]
  (merge (transform/calculate-bounds (vals @(:fixtures show)))
         {:visualizer-visible (into (sorted-map)
                                    (map (fn [head] [(:id head) head])
                                         (fixtures/visualizer-relevant (vals @(:fixtures show)))))
          :timestamp (at-at/now)}))

(defn patch-fixture!
  "Patch a fixture to a universe in [[*show*]] at a starting DMX
  channel address, at a particular point in space, with a particular
  orientation. Coordinates and rotations are with respect to the [show
  frame of
  reference](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space),
  and are in meters and radians. You can use [[transform/inches]]
  and [[transform/degrees]] to convert for you if desired."
  {:doc/format :markdown}
  [key fixture universe start-address & {:keys [x y z x-rotation y-rotation z-rotation]
                                         :or {x 0.0 y 0.0 z 0.0 x-rotation 0.0 y-rotation 0.0 z-rotation 0.0}}]
  {:pre [(some? *show*) (some? fixture) (some? universe) (integer? start-address) (<= 1 start-address 512)]}
  (when-not (contains? (:universes *show*) universe)
    (throw (IllegalArgumentException. (str "Show does not contain universe " universe))))
  (let [positioned (transform/transform-fixture fixture x y z x-rotation y-rotation z-rotation)]
    (swap! (:fixtures *show*) #(patch-fixture-internal *show* % (keyword key)
                                                       (chan/patch-fixture positioned universe start-address next-id))))
  (swap! (:dimensions *show*) (constantly (calculate-dimensions *show*))))

(defn patch-fixture-group!
  "*Deprecated until it supports positioning each fixture.*

  Patch a fixture group to a universe in [[*show*]] at a starting DMX channel address.
  Names will be assigned by adding a hyphen and numeric suffix, starting with 1,
  to the key supplied. If an offset is supplied, it will be added to the starting
  address for each subsequent fixture; if not, the largest offset used by the
  fixture will be used to calculate a suitable offset."
  {:doc/format :markdown
   :deprecated true}
  ([key fixture universe start-address count]
   (patch-fixture-group! key fixture universe start-address count (apply max (map :offset (:channels fixture)))))
  ([key fixture universe start-address count offset]
   {:pre [(some? *show*) (some? fixture) (some? universe) (integer? start-address) (<= 1 start-address 512)
          (integer? count) (pos? count)]}
   (for [i (range count)]
     (patch-fixture! (keyword (str (name key) "-" (inc i))) fixture universe (+ start-address (* i offset))))))


(defn all-fixtures
  "Returns all fixtures patched into [[*show*]]."
  {:doc/format :markdown}
  []
   {:pre [(some? *show*)]}
  (vals @(:fixtures *show*)))

(defn fixtures-named
  "Returns all fixtures patched into [[*show*]] whose key matches the
  specified name, with an optional number following it, as would be
  assigned to a fixture group by [[patch-fixtures!]]"
  {:doc/format :markdown}
  [n]
  {:pre [(some? *show*) (some? n)]}
  (let [pattern (re-pattern (str (name n) "(-\\d+)?"))]
    (reduce (fn [result [k v]] (if (re-matches pattern (name k))
                                 (conj result v)
                                 result)) [] @(:fixtures *show*))))

;; TODO: Provide general regex search of fixtures? Provide named fixture groups?

(defn active-effect-keys
  "Returns a set of the keywords assigned to all currently-active
  effects."
  [show]
  (set (map :key (:meta @(:active-effects show)))))

(defn profile-show
  "Gather statistics about the performance of generating and sending a
  frame of DMX data to the universes [[*show*]]."
  {:doc/format :markdown}
  ([]
   (profile-show 100))
  ([iterations]
   {:pre [(some? *show*) (integer? iterations) (pos? iterations)]}
   (let [buffers (create-buffers *show*)]
     (profile :info :Frame (dotimes [i iterations] (send-dmx *show* buffers))))))

(defn blackout-universe
  "Sends zero to every channel of the specified universe. Will be
  quickly overwritten if there are any active shows transmitting to
  that universe."
  [universe]
  {:pre [(some? universe)]}
  (let [levels (byte-array 512)]
    (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil)))

(defn blackout-show
  "Sends zero to every channel of every universe associated
  with [[*show*]]. Will quickly be overwritten if the show is running
  and there are any active effects, so this is mostly useful when a
  show has been suspended and you want to darken the lights it left
  on."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (doseq [universe (:universes *show*)]
    (blackout-universe universe)))

(defonce ^{:doc "Holds the registered shows, if any, for display in the web server."}
  shows (atom {}))

(defn register-grid-controller
  "Add a cue grid controller to the list available for linking in the
  web interface. The argument must implement the [[IGridController]]
  protocol."
  {:doc/format :markdown}
  [controller]
  {:pre [(some? *show*) (satisfies? controllers/IGridController controller)]}
  (swap! (:grid-controllers *show*) conj controller))

(defn unregister-grid-controller
  "Remove a cue grid controller from the list available for linking in the
  web interface."
    {:doc/format :markdown}
  [controller]
  {:pre [(some? *show*) (satisfies? controllers/IGridController controller)]}
  (swap! (:grid-controllers *show*) disj controller))

(defn register-show
  "Add a show to the list of available shows for the web interface."
  [show description]
  (swap! shows assoc (:id show) [show description]))

(defn unregister-show
  "Remove a show from the list of available shows for the web interface."
  [show]
  (swap! shows dissoc (:id show)))
