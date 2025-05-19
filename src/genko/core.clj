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
        ;; Endpoint is supposed to start with slash:
        url (str base-url endpoint)
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body-string (json/generate-string body)
        response (client/post url {:headers headers :body body-string})
        result (json/parse-string (:body response) true)]
    result))


;; See OpenAI function calling [1].   Beware of the schema differences
;; between Responses- and historical Chat Completions APIs used here:
;;
;;   [{:type "function", :function {:name ...}}, ...]
;;
;; See  the Cookbook  example [2].   BTW, not  every model  can tools.
;; Even fewer can do it well.   Ministral-3B on Azure can do some tool
;; calling, Phi-4 complains it was  not starte with the correct flags,
;; however.
;;
;; Wie want to map names to schema  AND code, so the `tool-map` is not
;; identical to the list of tools as  in the manual [1]. We derive the
;; list from the map when calling LLM later.
;;
;; Keys need to be strings, as this is how they come from LLM. We will
;; use these strings to look code up. Snake case seems to work too.
;;
;; [1] https://platform.openai.com/docs/guides/function-calling
;; [2] https://cookbook.openai.com/examples/how_to_call_functions_with_chat_models
(def tool-map
  {"get-weather"
   {:tool (fn [arguments]
            "It is sunny, humid, 24 Celsius!")

    ;; OpenAI Schema without `:name` to keep it DRY. The `:name` will
    ;; be set later equal to the key of the this `tool-map` entry.
    :schema {:description "Get current temperature for a given location."
             :parameters {:type "object"
                          :properties {:location {:type "string"
                                                  :description "City and country e.g. Bogotá, Colombia"}}
                          :required ["location"]
                          :additionalProperties false}
             ;; Ask LLM to actually enforce schema on arguments:
             :strict true}}

   "get-current-date-and-time"
   {:tool (fn [arguments]
            (str (new java.util.Date)))

    :schema {:description "Get current date and time."
             :parameters {:type "object"
                          :properties {}
                          :required []
                          :additionalProperties false}
             :strict true}}})


;; `chat-completion` now takes a list of messages as context, not just
;; a single prompt.
(defn- chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `options` is a map with :api-key, :base-url, and :model. `messages` is a list
  of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
  [options messages]
  (let [model (:model options)
        body {:model model
              :messages messages
              :tools (for [[fn-name fn-map] tool-map
                           :let [schema (:schema fn-map)]]
                       {:type "function"
                        :function (assoc schema :name fn-name)})}
        result (api-call options "/chat/completions" body)]
    (if (:verbose options)
      (pp/pprint {:Q body :A result}))
    (get-in result [:choices 0 :message])))

;; The output of `chat-completion`, a message, may have different
;; structure. Here two most relevant cases:
(comment
  ;; 1. Text content:
  {:content "I am an AI-powered assistant.",
   :role "assistant",
   :tool_calls nil,
   :function_call nil,
   :annotations []}

  ;; 2. Request for tool calls:
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
  user enters an empty prompt."
  [options turn messages]

  (loop [turn turn
         messages messages]
    (case turn
      ;; User turn.
      :user
      (do
        (print "USER: ")
        (flush)
        (let [prompt (read-line)]
          (if (str/blank? prompt)
            messages
            (let [messages (conj messages {:role "user"
                                           :content prompt})]
              (recur :assistant messages)))))

      ;; Assistant turn. Actuall LLM call here.
      :assistant
      (let [response (chat-completion options messages)

            ;; We must keep the response of the model for it to be fully
            ;; context aware:
            messages (conj messages response)]

        ;; An assistant message with `tool_calls` must be followed by
        ;; tool messages responding to each `tool_call_id`.  Ideally one
        ;; would need to ask the consent of the user to execute tools.
        (if-let [tool-calls (seq (:tool_calls response))]
          (let [messages (into messages
                               (for [tool-call tool-calls
                                     :let [{:keys [id function]} tool-call
                                           {:keys [name arguments]} function
                                           tool (:tool (tool-map name))]]
                                 {:role "tool"
                                  :name name
                                  :tool_call_id id
                                  :content (tool arguments)}))]
            ;; FIXME: potentially infinite recursion here if LLM never
            ;; stops calling tools!
            (recur :assistant messages))
          (do
            (println "ASSISTANT:" (:content response))
            (recur :user messages)))))))


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
         options (read-config-file)
         response (chat-completion options messages)]
     (:content response))))


(comment
  (llm-apply "What is the capital of France?")
  =>
  "The capital of France is Paris."

  (let [readme (slurp "README.md")
        prompt (str "Summarize the following text:\n" readme)]
    (llm-apply prompt))
  =>
  "Genko is a simple command-line tool designed ...")


;; NOTE: API key may leaks to stdout if OPENAI_API_KEY is set.
(defn -main [& args]
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
      (chat-with-user options
                      :user
                      [{:role "system"
                        :content "You are a helpful assistant. Reply in Markdown!"}])
      (println "Errors:" (str/join \newline errors)))))
