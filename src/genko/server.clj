;;
;; Implements basic /chat/completions and /models endpoints of the
;; OpenAI server protocoll. Sometime. So far these are only stubs.
;;
;;   $ curl -s http://localhost:3000/v1/models | jq
;;   $ curl -sXPOST http://localhost:3000/v1/chat/completions | jq
;;
(ns genko.server
  (:require
   [cheshire.core :as json]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as response]
   [compojure.core :as cc]
   [compojure.route :as route]))


(defn chat-completions-handler
  "Stub handler for /chat/completions endpoint."
  [request]
  ;; TODO: Implement actual logic
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:id "chatcmpl-stub"
           :object "chat.completion"
           :created (quot (System/currentTimeMillis) 1000)
           :model "gpt-4o"
           :choices [{:index 0
                      :message {:role "assistant"
                                :content "This is a stub response."}
                      :finish_reason "stop"}]})})


(defn models-handler
  "Stub handler for /models endpoint."
  [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:object "list"
           :data [{:id "gpt-4o"
                   :object "model"
                   :created 0
                   :owned_by "openai"
                   :permission []}]} )})


(cc/defroutes app-routes
  (cc/POST "/v1/chat/completions" request (chat-completions-handler request))
  (cc/GET "/v1/models" request (models-handler request))
  (route/not-found (response/not-found "Not Found")))


(defn start-server
  "Starts the HTTP server on the given port."
  ([]
   (start-server 3000))
  ([port]
   (run-jetty #'app-routes {:port port :join? false})))


;; Optionally, add a -main entry point for CLI usage
(defn main [& [port]]
  (let [port (if port (Integer/parseInt port) 3000)]
    (println (str "Starting server on port " port "..."))
    (start-server port)))
