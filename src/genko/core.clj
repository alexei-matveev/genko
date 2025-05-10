(ns genko.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]))


;; chat-completion now takes a list of messages as context, not just a single prompt.
(defn chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `config` is a map with :api-key and :base-url.  `messages` is a list
  of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
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
  "Interactive chat loop: repeatedly reads user input,
  extends context, and prints responses.  Conversation ends when the
  user enters an empty prompt.  Initializes messages with a system
  prompt."
  [config]
  (let [system-prompt "You are a helpful assistant. Reply in Markdown!"
        system-message {:role "system"
                        :content system-prompt}]
    (loop [messages [system-message]]
      (print "Enter your prompt: ")
      (flush)
      (let [prompt (read-line)]
        (if (clojure.string/blank? prompt)
          (println "Exiting chat.")
          (let [new-messages (conj messages {:role "user" :content prompt})
                response (chat-completion config new-messages)
                updated-messages (conj new-messages {:role "assistant" :content response})]
            (println "OpenAI response:\n" response)
            (recur updated-messages)))))))


(defn -main
  "Main entry point."
  [& _]
  (let [config {:api-key (System/getenv "OPENAI_API_KEY")
                :base-url (System/getenv "OPENAI_API_BASE_URL")}]
    (chat-with-user config)))
