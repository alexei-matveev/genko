(ns genko.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]))


;; chat-completion now takes a list of messages as context, not just a single prompt.
(defn chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `config` is a map with :api-key and :base-url.
  `messages` is a list of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
  [config messages]
  (let [model "gpt-4o"
        api-key (:api-key config)
        base-url (:base-url config)
        url (str base-url "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body (json/generate-string
               {:model model
                :messages messages})
        response (client/post url {:headers headers :body body})
        result (json/parse-string (:body response) true)]
    (get-in result [:choices 0 :message :content])))


(defn chat-with-user
  "Reads a prompt from stdin, calls chat-completion, and prints the response."
  [config]
  (print "Enter your prompt: ")
  (flush)
  (let [prompt (read-line)
        messages [{:role "user" :content prompt}]
        response (chat-completion config messages)]
    (println "OpenAI response:\n" response)))


(defn -main
  "Main entry point."
  [& _]
  (let [config {:api-key (System/getenv "OPENAI_API_KEY")
                :base-url (System/getenv "OPENAI_API_BASE_URL")}]
    (chat-with-user config)))

