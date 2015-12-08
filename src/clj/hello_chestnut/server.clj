(ns hello-chestnut.server
  (:require [clojure.java.io :as io]
            [hello-chestnut.dev :refer [is-dev? inject-devmode-html browser-repl start-figwheel]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :refer [resources]]
            [net.cgrand.enlive-html :refer [deftemplate]]
            [net.cgrand.reload :refer [auto-reload]]
            [ring.middleware.reload :as reload]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [environ.core :refer [env]]
            [org.httpkit.server :refer [run-server]]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit      :refer (sente-web-server-adapter)])
  (:gen-class))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! sente-web-server-adapter {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(deftemplate page (io/resource "index.html") []
  [:body] (if is-dev? inject-devmode-html identity))

(defroutes routes
  (resources "/")
  (resources "/react" {:root "react"})
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  (GET "/*" req (page)))

(def http-handler
  (if is-dev?
    (-> (reload/wrap-reload (wrap-defaults #'routes api-defaults))
        ring.middleware.keyword-params/wrap-keyword-params
        ring.middleware.params/wrap-params)
    (-> (wrap-defaults routes api-defaults)
        ring.middleware.keyword-params/wrap-keyword-params
        ring.middleware.params/wrap-params)))

(defn start-broadcaster! []
  (go-loop [i 0]
    (<! (async/timeout 10000))
    (println "Broadcasting server>user: %s" @connected-uids)
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid
        [:some/broadcast
         {:what-is-this "A broadcast pushed from server"
          :how-often    "Every 10 seconds"
          :to-whom uid
          :i i}]))
    (recur (inc i))))

(defn run-web-server [& [port]]
  (let [port (Integer. (or port (env :port) 10555))]
    (println (format "Starting web server on port %d." port))
    (run-server http-handler {:port port :join? false}))
  (start-broadcaster!)
  (println "done booting"))

(defn run-auto-reload [& [port]]
  (auto-reload *ns*)
  (start-figwheel))

(defn run [& [port]]
  (when is-dev?
    (run-auto-reload))

  (run-web-server port))



(defn -main [& [port]]
  (run port))
