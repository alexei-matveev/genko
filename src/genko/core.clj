(ns genko.core
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [sci.core :as sci]
   [clojure.tools.logging :as log]))


(def ^:private default-options
  {:base-url (or (System/getenv "OPENAI_BASE_URL")
                 "http://localhost:3000")
   :api-key (or (System/getenv "OPENAI_API_KEY")
                "sk-1234")
   :model "gpt-4.1"})

(defn- api-call
  "Call OpenAI API"
  [options endpoint body]
  (let [options (or options default-options)
        api-key (:api-key options)
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
             :strict true}}


   ;; No slashes are allowed in function names:
   ;;
   ;;   Expected a string that matches the pattern '^[a-zA-Z0-9_\\\\.-]+$'.
   ;;
   ;; Thus you cannot name a funciton
   ;; "sci.core/eval-string". See Babashka SCI [1].
   ;;
   ;; [1] https://github.com/babashka/SCI
   "sci.core--eval-string"
   {:tool
    (fn [arguments]
      (let [arguments (json/parse-string arguments true)
            {:keys [clojure-code]} arguments]

        ;; FIXME: Remote Code execution in its purest form here! SCI
        ;; ist somewhat better than a plain (eval (read-string ...))
        ;; but still!
        (let [value (sci/eval-string clojure-code
                                     {:namespaces {'Math {'sin Math/sin
                                                          'cos Math/cos
                                                          'pow Math/pow}}})]
          (log/warn "sci.core--eval-string:" clojure-code "=>" value)
          (str
           "#### Additional Context\n\n"
           "Clojure code " clojure-code " evaluates to " value
           " --- cite this but only when asked how you computed the value!"))))

    :schema
    {:description "Evaluate Clojure code in restricted interpreter. Mostly for simple arithmetics."
     :parameters {:type "object"
                  :properties {:clojure-code
                               {:type "string"
                                :description "Clojure code consisting of arithmetic expressions."}}
                  :required ["clojure-code"]
                  :additionalProperties false}
     :strict true}}})


;; `chat-completion` now takes a list of messages as context, not just
;; a single prompt.
(defn chat-completion
  "Call OpenAI Chat Completion API with a list of messages as context.
  `options` is a map with :api-key, :base-url, and :model. `messages` is a list
  of message maps, e.g. [{:role \"user\" :content \"Hello\"}]."
  [options messages]
  (let [options (or options default-options)
        model (:model options)
        body {:model model
              :messages messages
              :tools (for [[fn-name fn-map] tool-map
                           :let [schema (:schema fn-map)]]
                       {:type "function"
                        :function (assoc schema :name fn-name)})}
        result (api-call options "/chat/completions" body)]
    (when (:verbose options)
      (pp/pprint {:Q body :A result}))
    (get-in result [:choices 0 :message])))

(comment
  ;; The output of `chat-completion`, a message, may have different
  ;; structure. Here two most relevant cases:
  ;;
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
   :annotations []}

  ;; Example of a tool call:
  (chat-completion nil [{:role "user", :content "Factorial of 7?"}])
  =>
  {:content nil,
   :role "assistant",
   :tool_calls [{:function {:arguments "{\"clojure-code\":\"(reduce * (range 1 8))\"}",
                            :name "sci-eval-string"},
                 :id "call_lRQL3vvAb4XyUjChIep6qdh1",
                 :type "function"}],
   :function_call nil,
   :annotations []})


;; Call Tools available in the global `tool-map`.
(defn- call-tools [tool-calls]
  (for [tool-call tool-calls
        :let [{:keys [id function]} tool-call
              {:keys [name arguments]} function
              tool (:tool (tool-map name))]]

    ;; Value might be to long a text to log every tyme. In fact
    ;; `arguments` ist also potentially unbondend string from
    ;; untrusted source!
    (do
      (log/info "tool call" name ":" arguments)
      ;; FIXME: handle exceptions?
      (let [value (try
                    (tool arguments)
                    (catch Exception e
                      (.getMessage e)))]
        {:role "tool"
         :name name
         :tool_call_id id
         :content value}))))


;; This implementation of the `chat-with-tools` function hides from
;; the caller the exact results of the tool calls, as well as the fact
;; that the tools were used at all. The context containing the
;; transcript of all the tool calls is not visible to the caller. This
;; behavior may or may not be what the caller wants. Here is a use
;; case where the transcript would have been useful:
;;
;;   USER: sin(1.1)
;;   ASSISTANT: sin(1.1) ≈ 0.8912
;;   USER: how do you know that?
;;   ASSISTANT: I calculated sin(1.1) using the mathematical sine
;;     function in Clojure code: `(Math/sin 1.1)`, which evaluates to
;;     approximately 0.8912.
;;   USER: more digits?
;;   ASSISTANT: Sure! Using the calculation from earlier, the value of
;;     sin(1.1) is approximately: 0.8912073600614354
;;
;; Without a transcript, the LLM might tell you a story about the
;; Taylor series when you ask how it arrived at the result.
;;
(defn chat-with-tools
  "Let LLM chat with our tools, return when LLM responds with actual
  content"
  [options messages]

  (loop [messages messages]
    ;; Actuall LLM call here:
    (let [response (chat-completion options messages)]
      (if-let [tool-calls (seq (:tool_calls response))]
        ;; An assistant message with `tool_calls` must be followed by
        ;; tool messages responding to each `tool_call_id`.  Ideally
        ;; one would need to ask the consent of the user to execute
        ;; tools.  But first, we must keep the response of the model
        ;; for it to be fully context aware!
        (let [messages (conj messages response)

              ;; Now append results of tool calls:
              messages (into messages (call-tools tool-calls))]

          ;; FIXME: potentially infinite recursion here if LLM never
          ;; stops calling tools!
          (recur messages))

        ;; Regular case, LLM did not ask for tool calls, Just return
        ;; the response, hopefully with actual content.
        response))))

(comment
  (chat-with-tools nil [{:role "user", :content "what time ist it?"}])
  =>
  {:content "The current time is 20:55 (8:55 PM) on Saturday, August 16, 2025",
   :role "assistant",
   :tool_calls nil,
   :function_call nil,
   :annotations []})


(defn chat-with-user
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

      ;; Assistant turn. Actuall LLM call here. FIXME: maybe reuse
      ;; `chat-with-tools` here? Or do we need to keep all of the
      ;; conversation history in context, includinf all the tool
      ;; calls?
      :assistant
      (let [response (chat-completion options messages)

            ;; We must keep the response of the model for it to be fully
            ;; context aware:
            messages (conj messages response)]

        ;; An assistant message with `tool_calls` must be followed by
        ;; tool messages responding to each `tool_call_id`.  Ideally one
        ;; would need to ask the consent of the user to execute tools.
        (if-let [tool-calls (seq (:tool_calls response))]
          (let [messages (into messages (call-tools tool-calls))]
            ;; FIXME: potentially infinite recursion here if LLM never
            ;; stops calling tools!
            (recur :assistant messages))
          (do
            (println "ASSISTANT:" (:content response))
            (recur :user messages)))))))

