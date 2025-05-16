(ns genko.core
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp])
  (:gen-class))

(defn- api-call
  "Call OpenAI API"
  [options endpoint body]
  (let [api-key (:api-key options)
        base-url (:base-url options)
        ;; Endpoint ist supposed to start with Slash:
        url (str base-url endpoint)
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body-string (json/generate-string body)
        response (client/post url {:headers headers :body body-string})
        result (json/parse-string (:body response) true)]
    result))


;; Siehe Function Calling [1]. Ist die Doku fehelrhaft? Dort fehlt die
;; Struktur [{:type  "function", :function {...}}].  Dies widerspricht
;; zum Beispiel  dem Cookbook  Beispiel [2].  Nicht jedes  Modell kann
;; Tools. Noch weniger können es gut.
;;
;; [1] https://platform.openai.com/docs/guides/function-calling
;; [2] https://cookbook.openai.com/examples/how_to_call_functions_with_chat_models
(def tools
  [{:type "function"
    :function                           ; fehlt in Doku [1]
    {:name "get_weather"
     :description "Get current temperature for a given location."
     :parameters {:type "object"
                  :properties {:location {:type "string"
                                          :description "City and country e.g. Bogotá, Colombia"}}
                  :required ["location"]
                  :additionalProperties false}}}])


;; chat-completion now takes a list of messages as context, not just a
;; single prompt.
(defn- chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `options` is a map with :api-key, :base-url, and :model. `messages` is a list
  of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
  [options messages]
  (let [model (:model options)
        body {:model model
              :messages messages
              :tools tools}
        result (api-call options "/chat/completions" body)]
    (if (:verbose options)
      (pp/pprint {:Q body :A result}))
    (get-in result [:choices 0 :message])))

;; (chat-completion ...) Kann unterschiedliche Strukturen
;; liefern. Hier zwei relevante Fälle:
(comment
  ;; 1. Regelfall:
  {:content "I am an AI-powered assistant.",
   :role "assistant",
   :tool_calls nil,
   :function_call nil,
   :annotations []}

  ;; 2. Tool Call:
  {:content nil,
   :role "assistant",
   :tool_calls [{:function {:name "get_weather",
                            :arguments "{\"location\":\"Berlin, Germany\"}"},
                 :id "call_xyz",
                 :type "function"}],
   :function_call nil,
   :annotations []})


(defn- chat-with-user
  "Interactive chat loop: repeatedly reads user input,
  extends context, and prints responses.  Conversation ends when the
  user enters an empty prompt.  Initializes messages with a system
  prompt."
  [options]
  (let [system-prompt "You are a helpful assistant. Reply in Markdown!"
        system-message {:role "system"
                        :content system-prompt}]
    (loop [messages [system-message]]
      (print "USER: ")
      (flush)
      (let [prompt (read-line)]
        (if-not (str/blank? prompt)
          (let [messages (conj messages {:role "user"
                                         :content prompt})

                ;; Actuall LLM call here:
                response (chat-completion options messages)

                ;; We must keep the response of the model for it to be
                ;; fully context aware:
                messages (conj messages response)

                ;; An assistant message with 'tool_calls' must be
                ;; followed by tool messages responding to each
                ;; 'tool_call_id'. Ideally one would need to ask the
                ;; consent of the user to execute tools.
                messages (if-let [tool-calls (seq (:tool_calls response))]
                           (into messages
                                 (for [tool-call tool-calls]
                                   {:role "tool"
                                    :name (:name (:function tool-call))
                                    :tool_call_id (:id tool-call)
                                    :content "Error!"}))
                           messages)]
            (println "ASSISTANT:" (:content response))
            (recur messages)))))))


;; Sometimes it is more conventient to supply connections details in a
;; file:
(defn- read-config-file
  "Reads EDN config from ~/.genkorc.edn if it exists, returns a map or {}."
  []
  (let [file (io/file (System/getenv "HOME") ".genkorc.edn")]
    (if (.exists file)
      (edn/read-string (slurp file))
      {})))

(defn- llm-apply
  ([prompt] (llm-apply prompt []))
  ([prompt context]
   (let [messages (conj context {:role "user" :content prompt})
         options (read-config-file)]
     (chat-completion options messages))))

(comment
  ;; => "The capital of France is Paris."
  (llm-apply "What is the capital of France?")

  ;; => "Genko is a simple command-line tool designed ..."
  (let [readme (slurp "README.md")
        prompt (str "Summarize the following text:\n" readme)]
    (llm-apply prompt)))

(defn -main [& args]
  ;; NOTE: API key leaks to stdout on CLI parsing errors if
  ;; OPENAI_API_KEY is set:
  (let [cli-options [["-v" "--verbose" "Enable verbose mode"
                      :default false]
                     [nil "--model MODEL" "Language model"
                      :default "gpt-4o"]
                     [nil "--base-url BASE-URL" "Base URL"
                      :default (System/getenv "OPENAI_API_BASE_URL")]
                     [nil "--api-key API-KEY" "API key"
                      :default (System/getenv "OPENAI_API_KEY")]]
        cli-parsed (cli/parse-opts args cli-options)
        {:keys [options arguments summary errors]} cli-parsed]
    (if (:verbose options)
      (pp/pprint cli-parsed))
    ;; (println "Options:" options)
    ;; (println "Arguments:" arguments)
    ;; (println "Summary:")
    ;; (println summary)
    ;; (println "Errors:" errors)
    (if-not (seq errors)
      (chat-with-user options)
      (println "Errors:" (str/join \newline errors)))))
