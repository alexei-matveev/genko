;;
;; Implements basic /chat/completions and /models endpoints of the
;; OpenAI server protocoll. Sometime. So far these are only stubs.
;;
;;   $ curl -s http://localhost:3000/v1/models | jq
;;   $ curl -sXPOST http://localhost:3000/v1/chat/completions -d '{"messages":[{"role":"user","content":"are you human?"}]}' | jq
;;
(ns genko.server
  (:require
   [cheshire.core :as json]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as response]
   [compojure.core :as cc]
   [compojure.route :as route]))

(def ^:private MODEL "echo")

(defn- chat-completions-handler
  "Echoes the last message's content from the request."
  [request]
  (let [body (slurp (:body request))
        data (try (json/parse-string body true) (catch Exception _ {}))
        messages (:messages data)
        last-msg (last messages)
        last-content (:content last-msg)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:id "chatcmpl-echo"
             :object "chat.completion"
             :created (quot (System/currentTimeMillis) 1000)
             :model MODEL
             :choices [{:index 0
                        :message {:role "assistant"
                                  :content (or last-content "")}
                        :finish_reason "stop"}]})}))


(defn- models-handler
  "Stub handler for /models endpoint."
  [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:object "list"
           :data [{:id MODEL
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
   (start-server {:port 3000}))
  ([options]
   (let [port (:port options)]
     (run-jetty #'app-routes {:port port :join? false}))))


;; For your C-x C-e pleasure:
(comment
  (start-server))
