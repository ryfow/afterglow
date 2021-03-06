(ns afterglow.web.routes.home
  (:require [afterglow.web.layout :as layout]
            [afterglow.web.routes.show-control :as show-control]
            [afterglow.web.routes.web-repl :as web-repl]
            [afterglow.web.routes.visualizer :as visualizer]
            [afterglow.show :as show]
            [compojure.core :refer [defroutes GET POST]]
            [environ.core :refer [env]]
            [ring.util.http-response :refer [ok]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [clojure.java.io :as io]))

(defn home-page []
  (let [shows (map (fn [[show description]]
                     {:show show :description description})
                   (vals @show/shows))]
    (layout/render
     "home.html" {:shows shows
                  :docs (-> "docs/docs.md" io/resource slurp)
                  :version (env :afterglow-version)
                  :csrf-token *anti-forgery-token*})))

(defn about-page []
  (layout/render "about.html" {:csrf-token *anti-forgery-token*}))

(defn visualizer-page []
  (layout/render "visualizer.html" {:csrf-token *anti-forgery-token*}))

(defroutes home-routes
  (GET "/" [] (home-page))
  (GET "/show/:id" [id] (show-control/show-page id))
  (GET "/ui-updates/:id" [id] (show-control/get-ui-updates id))
  (POST "/ui-event/:id/:kind" [id kind :as req] (show-control/post-ui-event id kind req))
  (GET "/console" [] (web-repl/page))
  (POST "/console" [:as req] (web-repl/handle-command req))
  (GET "/about" [] (about-page))
  (GET "/visualizer/:id" [id] (visualizer/page id))
  (GET "/visualizer-update/:id" [id] (visualizer/update-preview id))
  (GET "/shaders/:id/fragment.glsl" [id] (visualizer/shader id)))




