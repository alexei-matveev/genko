;;
;; Implements basic /v1/chat/completions and /v1/models endpoints of
;; the OpenAI server protocoll. Liberal in what it accepts.
;;
;;   $ curl -s http://localhost:3000/v1/models | jq
;;   $ curl -sXPOST http://localhost:3000/v1/chat/completions -d '{"messages":[{"role":"user","content":"are you human?"}]}' | jq
;;
;; This would not be JSON, rather an SSE stream:
;;
;;   $ curl -vNXPOST http://localhost:3000/v1/chat/completions -d '{"messages":[{"role":"user","content":"tell me a story!"}],"stream":true}'
;;
(ns genko.server
  (:require
   [genko.core :as core]
   [cheshire.core :as json]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as response]
   [ring.middleware.cors :refer [wrap-cors]]
   [compojure.core :as cc]
   [compojure.route :as route]
   [clojure.java.io :as io]))

(def ^:private MODEL "genko")


(defn- echo-model
  "Takes a conversation and returns text"
  [messages]
  (let [last-msg (last messages)]
    #_(or (clojure.string/join " XXX " (map :content messages))
          "")
    (or (:content last-msg) "")))


(defn- real-model
  "Takes a conversation and returns text"
  [messages]
  ;; This is the location to augment or redesign context and pass it
  ;; to upstream LLM.
  (let [message (core/chat-completion nil messages)]
    (or (:content message)
        (if (:tool_calls message)
          "<llm expects us to call tools here>"
          ""))))


;; Much of what follows is the (unnecessary?) complexity to allow
;; clients that insist on streaming to talk to our server. Some
;; clients dont even allow to configure the `stream` option of the
;; OpenAI protocoll. You may skip to (return text)
;; and (chat-completions-handler request) on the first reading.
;;
;; Clojure protocoll function `write-body-to-stream` is already
;; defined for Clojure `ISeq` and that implementation does not flush
;; after each chunk. That is why we need an new Class and a protocoll
;; implementation.
;;
;; Similarly, `write-body-to-stream` is predefined for `InputStream`
;; and the implementation just copies all of it in bulk. This not
;; suitable for SSE streaming, right? We did try with connected
;; `PipedInputStream` and `PipedOutputStream` where we wrote and
;; flushed chunks to the latter with no success --- the body comes in
;; bulk on the receiver (curl) side.
;;
;; [1] https://github.com/ring-clojure/ring/blob/1.14.2/ring-core-protocols/src/ring/core/protocols.clj
(defrecord FlushingSeqBody [chunks])

(extend-protocol ring.core.protocols/StreamableResponseBody
  FlushingSeqBody
  (write-body-to-stream [body _response output-stream]
    (doseq [chunk (:chunks body)]
      ;; Write the chunk
      (.write output-stream
              (.getBytes ^String chunk))
      ;; Flush so it is sent immediately
      (.flush output-stream))))


;; Simulate streaming
(defn slow-lazy-seq [sequence]
  (lazy-seq
   (when-let [s (seq sequence)]
     (Thread/sleep 100)
     (cons (first s) (slow-lazy-seq (rest s))))))


;; https://github.com/ring-clojure/ring/issues/491
(defn example-handler [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   ;; ISeq Body will be delivered with Transfer-Encoding: chunked
   :body (->FlushingSeqBody
          (slow-lazy-seq
           (for [i (range 5)]
             (str "chunk " i "\n"))))})


(defn- sse-chunk
  "Formats a chunk of data for SSE."
  [data]
  (str "data: " (json/generate-string data) "\n\n"))


;; Simulate streaming by splitting the reply into words
(defn- stream
  "Stream completion request via SSE."
  [text]
  (let [words (clojure.string/split text #"\s+")
        bulk-chunks (for [[idx word] (map-indexed vector words)]
                      (sse-chunk
                       {:id (str "chatcmpl-echo-" idx)
                        :object "chat.completion.chunk"
                        :created (quot (System/currentTimeMillis) 1000)
                        :model MODEL
                        :choices [{:index 0
                                   :delta {:role (when (zero? idx) "assistant")
                                           :content (str word (when (< idx (dec (count words))) " "))}
                                   :finish_reason nil}]}))
        ;; Final chunk with finish_reason
        stop-chunk (sse-chunk
                    {:id (str "chatcmpl-echo-" (count words))
                     :object "chat.completion.chunk"
                     :created (quot (System/currentTimeMillis) 1000)
                     :model MODEL
                     :choices [{:index 0
                                :delta {}
                                :finish_reason "stop"}]})

        ;; End of SSE stream marker.  Note that there are no quotes
        ;; around sentinel values in the SSE stream. These quotes
        ;; would make sentinel values a valid JSON string. And
        ;; sentinels such as [DONE] are probably intentionaly chosen
        ;; not to be valid JSON. Thus cannot use `ssh-chunk` here:
        ;;
        ;;   (sse-chunk "[DONE]") => "data: \"[DONE]\"\n\n"
        ;;
        done-chunk "data: [DONE]\n\n"]
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"}
     :body (->FlushingSeqBody
            (slow-lazy-seq
             (concat bulk-chunks [stop-chunk done-chunk])))}))


(defn- return
  "Return non-streaming completion request"
  [text]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:id "chatcmpl-echo"
           :object "chat.completion"
           :created (quot (System/currentTimeMillis) 1000)
           :model MODEL
           :choices [{:index 0
                      :message {:role "assistant"
                                :content text}
                      :finish_reason "stop"}]})})


(defn- chat-completions-handler
  "Handle non-streaming and streaming completion request"
  [request]
  (let [body (slurp (:body request))
        data (try (json/parse-string body true) (catch Exception _ {}))
        messages (:messages data)
        text (real-model messages)]
    (if (:stream data)
      (stream text)
      (return text))))


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

  ;; To test streaming response without buffering here:
  ;;
  ;;   curl -vNXPOST http://localhost:3000/example
  ;;
  (cc/POST "/example" request (example-handler request))
  (route/not-found (response/not-found "Not Found")))


;; CORS Headers appear to be necessary if you want to try Open WebUI
;; with a Direct Connection from Browser to Localhost. NOTE: Wildcard
;; here allows any website you have open in your Browser to speak to
;; your server! FIXME: Auth implementieren?
(def app
  (-> app-routes
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :put :post :delete])))

;; You can interact with the server from the CLI like this:
;;
;;   $ lein run --base-url=http://localhost:3000/v1
;;
;; To start the server, execute (start-server) from Cider, or run:
;;
;;   $ lein run --server
;;
;; in the CLI. Test streaming like this:
;;
;;   $ curl -NXPOST http://localhost:3000/v1/chat/completions -d '{"model":"genko","messages":[{"role":"user","content":"are you human?"}],"stream":true}'
;;
(defn start-server
  "Starts the HTTP server on the given port."
  ([]
   (start-server {:port 3000}))
  ([options]
   (let [port (:port options)]
     (run-jetty #'app {:port port :join? false}))))


;; For your C-x C-e pleasure:
(comment
  (def _server (start-server))
  (.stop _server))
