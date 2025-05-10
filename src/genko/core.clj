(ns genko.core
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.tools.cli :as cli])
  (:gen-class))

(defn- api-call
  "Call OpenAI API"
  [config endpoint body]
  (let [api-key (:api-key config)
        base-url (:base-url config)
        ;; Endpoint ist supposed to start with Slash:
        url (str base-url endpoint)
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body-string (json/generate-string body)
        response (client/post url {:headers headers :body body-string})
        result (json/parse-string (:body response) true)]
    result))

;; chat-completion now takes a list of messages as context, not just a
;; single prompt.
(defn- chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `config` is a map with :api-key, :base-url, and :model. `messages` is a list
  of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
  [config messages]
  (let [model (:model config)
        body {:model model
              :messages messages}
        result (api-call config "/chat/completions" body)]
    (get-in result [:choices 0 :message :content])))


(defn- chat-with-user
  "Interactive chat loop: repeatedly reads user input,
  extends context, and prints responses.  Conversation ends when the
  user enters an empty prompt.  Initializes messages with a system
  prompt."
  [config]
  (let [system-prompt "You are a helpful assistant. Reply in Markdown!"
        system-message {:role "system"
                        :content system-prompt}]
    (loop [messages [system-message]]
      (print "USER: ")
      (flush)
      (let [prompt (read-line)]
        (if (str/blank? prompt)
          (println "Exiting chat.")
          (let [new-messages (conj messages {:role "user" :content prompt})
                response (chat-completion config new-messages)
                updated-messages (conj new-messages {:role "assistant" :content response})]
            (println "ASSISTANT:" response)
            (recur updated-messages)))))))


(defn -main [& args]
  ;; NOTE: API key leaks to stdout on CLI parsing errors if
  ;; OPENAI_API_KEY is set:
  (let [cli-options [[nil "--model MODEL" "Language model"
                      :default "gpt-4o"]
                     [nil "--base-url BASE-URL" "Base URL"
                      :default (System/getenv "OPENAI_API_BASE_URL")]
                     [nil "--api-key API-KEY" "API key"
                      :default (System/getenv "OPENAI_API_KEY")]]
        {:keys [options arguments summary errors]} (cli/parse-opts args cli-options)]
    (println "Options:" options)
    (println "Arguments:" arguments)
    (println "Summary:")
    (println summary)
    (println "Errors:" errors)
    (cond
      (seq errors) (println "Errors:"
                            (str/join \newline errors))
      :else (chat-with-user options))))
