# Afterglow

A Clojure take on DMX lighting control, leveraging the [Open Lighting Architecture](https://www.openlighting.org/ola/), and pieces of the [Overtone](https://github.com/overtone/overtone) toolkit. For efficiency, Afterglow uses [Protocol Buffers](https://developers.google.com/protocol-buffers/docs/overview) to communicate with the `olad` process running on the local machine via its [RPC Service](https://docs.openlighting.org/doc/latest/rpc_system.html).

## Status

I am very rapidly fleshing this out; it is a bare skeleton right now, but I wanted to make sure I understood how to get it up on GitHub and Clojars, to share with the world, and to help motivate me. There will be a lot more functionality, better examples, and more explanation of how to use it, very soon. In particular, the modeling of fixtures, channels, etc. is in an early form now, and I expect there will be drastic changes as I gain experience with how I want to use them, and build macros and other tools to make them easier to define.

## Installation

1. [Install OLA](https://www.openlighting.org/ola/getting-started/downloads/); I recommend using [Homebrew](http://brew.sh) which lets you simply `brew install ola`. Once you launch the `olad` server you can interact with its embedded [web server](http://localhost:9090/ola.html), which is very helpful in seeing whether anything is working; you can even watch live DMX values changing.
2. For now set up a Clojure project using [Leiningen](http://leiningen.org).
3. Add this project as a dependency: [![Clojars Project](http://clojars.org/afterglow/latest-version.svg)](http://clojars.org/afterglow)

Eventually you may be able to download binary distributions from somewhere.

## Usage

Given its current development phase, you will want to use Afterglow in a Clojure repl.

    ;; The next two lines are not needed if you are using Leiningen to get your repl,
    ;; since the project is configured to start you in this namespace for convenience.
    (require 'afterglow.examples)
    (in-ns 'afterglow.examples)
    
    ;; Start the sample show which runs on DMX universe 1. You will want to have OLA
    ;; configured to at least have an ArtNet universe with that ID so you can watch the
    ;; DMX values using its web interface. It would be even better if you had an actual
    ;; DMX interface hooked up, and changed the definition of sample-rig to include
    ;; some real lights you have connected.
    (show/start! sample-show)
    
    ;; Assign a nice cool blue color to all lights in the sample show
    (show/add-function! sample-show :color blue-cue)
    
    ;; But I'm still not seeing anything? Oh! The dimmers...
    ;; Set them all to full:
    (show/add-function! sample-show :master (master-cue 255))
    
    ;; We can make that a little dimmer
    (show/add-function! sample-show :master (master-cue 200))
    
    ;; Change the color to orange
    (show/add-function! sample-show :color (global-color-cue :orange))
    
    ;; Let's get a little fancy and ramp the dimmers up on a sawtooth curve each beat:
    (show/add-function! sample-show :master
                        (master-cue (params/build-oscillated-param
                                     (oscillators/sawtooth-beat))))
    
    ;; Slow that down a little:
    (afterglow.rhythm/metro-bpm (:metronome sample-show) 70)
    
    ;; If you have DJ software or a mixer sending you MIDI clock data, you can sync
    ;; the show's BPM to it:
    (show/sync-to-external-clock sample-show
                                 (afterglow.midi/sync-to-midi-clock "traktor"))

    ;; If you have Pioneer gear sending you Pro DJ Link packets, you can sync even
    ;; more precisely:
    (show/sync-to-external-clock sample-show
                                 (afterglow.dj-link/sync-to-dj-link "DJM-2000"))
    
    ;; To check on the sync status:
    (show/sync-status sample-show)
    ; -> {:type :midi, :status "Running, clock pulse buffer is full."}
    ; -> {:type :dj-link, :status "Network problems? No DJ Link packets received."}

    ;; How about a nice cycling rainbow color fade?
    (def hue-param (params/build-oscillated-param
      (oscillators/sawtooth-bar) :max 360))
    (show/add-function! sample-show :color
      (global-color-cue (params/build-color-param :s 100 :l 50 :h hue-param)))

    ;; Or maybe a color that wavers near yellow?
    (def yellow (create-color :yellow))
    (def hue-param (params/build-oscillated-param
      (oscillators/sine-beat) :min (hue (adjust-hue yellow -5))
                              :max (hue (adjust-hue yellow 5))))
    (show/add-function! sample-show :color
      (global-color-cue (params/build-color-param :s 100 :l 50 :h hue-param)))

    ;; Terminate the effect handler thread:
    (show/stop! sample-show)
    
    ;; And darken the universe we were playing with...
    (show/blackout-show sample-show)

If you have a web browser open on [your OLA daemon](http://localhost:9090/ola.html)'s DMX monitor for Universe 1, you will see the values for channels changing, then ramping up quickly, then a little more slowly after you change the BPM. Alter the example to use a universe and channels that you will actually be able to see with a connected fixture, and watch Clojure seize control of your lights!

## Options

FIXME: listing of options this app accepts once it can run as a standalone app.

## Examples

### Working with Color

Cues that assign color to lights are designed to leverage the
[jolby/colors](https://github.com/jolby/colors) library. In addition
to creating colors by name, as in the Usage examples, you can create
them by hex string, RGB values, and, most usefully when thinking about
how to mix and fade them,
[HSL](http://en.wikipedia.org/wiki/HSL_and_HSV) (Hue, Saturation, and
Lightness). So, if you wanted a cue that shifts back and forth around
yellow, and don't remember the hue value of yellow, you could do
something like this:

    (use 'com.evocomputing.colors)
    (let [yellow (create-color :yellow)]
      (show/add-function! sample-show :color
                          (hue-oscillator (oscillators/sine-beat)
                                          (show/all-fixtures sample-show)
                                          :min (hue (adjust-hue yellow -5))
                                          :max (hue (adjust-hue yellow 5)))))

You can add lighten it up by adding something like `:lightness 70` to the
`hue-oscillator` call, darken it a bunch with `:lightness 20` or desaturate
it a touch with `:saturation 80`... For more options and ideas, delve into
the colors library API documentation, and the various oscillators that
Afterglow makes available to you.

...

### Bugs

...

### Ideas

* Create a project Wiki on GitHub and move this kind of discussion to it:
* Tons of oscillators and combinators for them, with convenient initializers.
* Model moving head location and position, so they can be panned and aimed in a coordinated way.
    - [Wikipedia](http://en.wikipedia.org/wiki/Rotation_formalisms_in_three_dimensions) has the most promising overview of what I need to do.
    - If I can’t find anything Clojure or Java native, [this C# library](http://www.codeproject.com/Articles/17425/A-Vector-Type-for-C) might serve as a guide.
    - Or perhaps [this paper](https://www.fastgraph.com/makegames/3drotation/) with its associated C++ source.
    - Or [this one](http://inside.mines.edu/fs_home/gmurray/ArbitraryAxisRotation/) which is already Java but seems to only perform, not calculate, rotations.
    - Use iOS device to help determine orientation of fixture: Hold phone upright facing stage from audience perspective to set reference attitude; move to match a landmark on the fixture (documented in the fixture definition), and have phone use [CoreMotion](https://developer.apple.com/library/ios/documentation/CoreMotion/Reference/CMAttitude_Class/index.html#//apple_ref/occ/instm/CMAttitude/multiplyByInverseOfAttitude:) `CMAttitude` `multiplyByInverseOfAttitude` to determine the difference.
    - The more I investigate, the more it looks like [Java3D’s](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/) [Transform3D](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/javax/media/j3d/Transform3D.html) object is going to handle it for me, which is very convenient, as it is already available in Clojure. To combine transformations, just multiply them together (with the `mul` method).
* Model colors, support setting via HSB, eventually maybe even model individual LED colors, especially for fixtures with more than three colors.
* Sparkle effect, essentially a particle generator with configurable maximum brightness, fade time, distribution. Work both with arbitrary channel list, and with spatially mapped origin/density; as single intensity, or spatially mapped hue/saturation patterns.
* Use [claypoole](https://clojars.org/com.climate/claypoole) for parallelism.
* Add OSC support (probably using [Overtone&rsquo;s implementation](https://github.com/rosejn/osc-clj)) for controller support, and MIDI as well.
* Add a user interface using [Luminus](http://www.luminusweb.net/docs).
* Serious references for color manipulation, but in [Julia](https://github.com/timholy/Color.jl).
* Absolutely amazing reference on [color vision](http://handprint.com/LS/CVS/color.html)! Send him a note asking if he knows where I can find an algorithm for using arbitrary LEDs to make an HSL color!
* When it is time to optimize performance, study the [type hints](http://clojure.org/java_interop#Java%20Interop-Type%20Hints) interop information looks very informative and helpful.

### References

* Clojure implementation of Protocol Buffers via [lein-protobuf](https://github.com/flatland/lein-protobuf) and [clojure-protobuf](https://github.com/flatland/clojure-protobuf).
* The incomplete [Java OLA client](https://github.com/OpenLightingProject/ola/tree/master/java).

### Related Work

* Am fixing the [broken Max external](https://wiki.openlighting.org/index.php/OlaOutput_Max_External). It fails because it tries to load an outdated version of the `libproto` DLL in a hardcoded bad library path. I have now been able to check out the source into `old/svn/olaoutput-read-only` and succeeded at building and fixing it. I separately downloaded the [Max 6.1.4 SDK](https://cycling74.com/downloads/sdk/). The maxhelp file has some seeming errors in it: a "state" message which seems unimplemented, and a second inlet which appears to really just be an argument. I have offered to share my changes and explore fixing the help unless the authors want to, on the open lighting Google group. They have, at my prompting, migrated to github, and I am committing my changes to a fork, in `git/olaoutput`.

## License

Copyright © 2015 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
