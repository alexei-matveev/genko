(ns genko.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]))


(defn chat-completion
  "Example usage of OpenAI Chat Completion API."
  [config prompt]
  (let [model "gpt-4o"
        api-key (:api-key config)
        base-url (:base-url config)
        url (str base-url "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body (json/generate-string
               {:model model
                :messages [{:role "user" :content prompt}]})
        response (client/post url {:headers headers :body body})
        result (json/parse-string (:body response) true)]
    (get-in result [:choices 0 :message :content])))


(defn -main
  "Main entry point: reads a prompt from stdin, calls chat-completion, prints result."
  [& _]
  (let [config {:api-key (System/getenv "OPENAI_API_KEY")
                :base-url (System/getenv "OPENAI_API_BASE_URL")}]
    (print "Enter your prompt: ")
    (flush)
    (let [prompt (read-line)
          response (chat-completion config prompt)]
      (println "OpenAI response:\n" response))))

