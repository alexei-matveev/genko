(ns genko.core
  (:require
   [genko.tools :as tools]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.pprint :as pp]
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
              :tools (tools/declare-tools)}
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
              messages (into messages (tools/call-tools tool-calls))]

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
          (let [messages (into messages (tools/call-tools tool-calls))]
            ;; FIXME: potentially infinite recursion here if LLM never
            ;; stops calling tools!
            (recur :assistant messages))
          (do
            (println "ASSISTANT:" (:content response))
            (recur :user messages)))))))

