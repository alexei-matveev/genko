(ns genko.core
  (:require
   [genko.tools :as tools]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.pprint :as pp]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]))


;; You might want to start Clojure nREPL from a schell with these
;; environment variables, e.g. `lein repl :headless :port 1122`, and
;; M-x cider-connect-clj to it.
(def ^:private default-options
  {:base-url (or (System/getenv "OPENAI_BASE_URL")
                 "http://localhost:3000")
   :api-key (or (System/getenv "OPENAI_API_KEY")
                "sk-1234")
   :model (or (System/getenv "OPENAI_MODEL")
              "gpt-5-chat")})


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


;; Yet unused:
(defn api-stream
  "Call OpenAI-compatible API endpoint with SSE streaming, returning a
  lazy sequence of parsed JSON chunks."
  [options endpoint body]
  (let [options (or options default-options)
        api-key (:api-key options)
        base-url (:base-url options)
        url (str base-url endpoint)
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body-string (json/generate-string body)
        ;; FIXME: (if (:stream body) ...)? Also streaming might only
        ;; make sense for /v1/completions. But this is the only
        ;; endpoint we use here so far.
        response (client/post url {:headers headers
                                   :body body-string
                                   :as :stream ; want body as InputStream
                                   :throw-exceptions true})]
    (let [reader (io/reader (:body response))]
      (letfn [(sse-lines []
                (lazy-seq
                 (when-let [line (.readLine reader)]
                   (cond
                     (.startsWith line "data: ")
                     (let [data (subs line 6)]
                       (if-not (= data "[DONE]")
                         (cons (json/parse-string data true)
                               (sse-lines))))
                     :else (sse-lines)))))]
        (sse-lines)))))


(comment
  ;; Example of a first chunk:
  ({:id "chatcmpl-C6ISgK0mF4LROonu3hrkgGINeMXmJ",
    :created 1755616970,
    :model "gpt-4.1-2025-04-14",
    :object "chat.completion.chunk",
    :system_fingerprint "fp_c79ab13e31",
    :choices [{:content_filter_results {#_(...)},
               :index 0,
               :delta {:content "No", :role "assistant"}}]}

   ;; Example of the last chunk preceeding the SSE sentinel "[DONE]":
   {:id "chatcmpl-C6ISgK0mF4LROonu3hrkgGINeMXmJ",
    :created 1755616970,
    :model "gpt-4.1-2025-04-14",
    :object "chat.completion.chunk",
    :system_fingerprint "fp_c79ab13e31",
    :choices [{:finish_reason "stop",
               :index 0,
               :delta {}}]})

  ;; The answer for such a simple question comes back in 25-50 chunks!
  ;; Do we really want to cause that much work? Note that (the last?)
  ;; delta might be nil!
  (let [chunks (api-stream nil
                           "/chat/completions"
                           {:model "gpt-4.1"
                            :messages [{:role "user", :content "are you human?"}]
                            :stream true})
        deltas (for [chunk chunks]
                 (-> chunk :choices (get 0) :delta :content))]
    ;; [(count chunks) #_(first chunks) #_(last chunks)]
    deltas))

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


;; The difference between `chat-with-tools` and `chat-with-tools*` is
;; that the former hides from the caller the exact results of the tool
;; calls, as well as the fact that the tools were used at all. The
;; context containing the transcript of all the tool calls is not
;; visible to the caller. This behavior may or may not be what the
;; caller wants. Here is a use case where the transcript would have
;; been useful:
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
(defn chat-with-tools*
  "Let LLM chat with our tools, return the whole conversation when LLM
  responds with actual content"
  [options messages]

  (loop [messages messages]
    ;; Actuall LLM call here:
    (let [response (chat-completion options messages)
          ;; Push responce onto "context stack":
          messages (conj messages response)]

      ;; An assistant message with `tool_calls` must be followed by
      ;; tool messages responding to each `tool_call_id`.  Ideally one
      ;; would need to ask the consent of the user to execute tools.
      ;;
      ;; Push results of tool calls onto the "context stack" and
      ;; recur.  FIXME: potentially infinite recursion here if LLM
      ;; never stops calling tools!
      (if-let [tool-calls (seq (:tool_calls response))]
        (let [messages (into messages (tools/call-tools tool-calls))]
          (recur messages))

        ;; Regular case, LLM did not ask for tool calls, Just return
        ;; the whole conversation, hopefully with actual content.
        ;;
        ;; Maybe a yet better abstraction would be to return response
        ;; AND an updated context. The response duplicated at the top
        ;; of the stack would be just a regular case of updating the
        ;; context.
        messages))))


(defn chat-with-tools
  "Historically we returned only the last message"
  [options messages]
  (last (chat-with-tools* options messages)))


(comment
  (chat-with-tools nil [{:role "user", :content "what time ist it?"}])
  =>
  {:content "The current time is 20:55 (8:55 PM) on Saturday, August 16, 2025",
   :role "assistant",
   :tool_calls nil,
   :function_call nil,
   :annotations []}

  (chat-with-tools* nil [{:role "user", :content "what time is it?"}])
  =>
  [{:role "user",
    :content "what time is it?"}
   {:content nil,
    :role "assistant",
    :tool_calls [{:function {:arguments "{}",
                             :name "get-current-date-and-time"},
                  :id "call_uK6KBLWbE0dD1f1Xz6nmGfXS",
                  :type "function"}],
    :function_call nil,
    :annotations []}
   {:role "tool",
    :name "get-current-date-and-time",
    :tool_call_id "call_uK6KBLWbE0dD1f1Xz6nmGfXS",
    :content "Thu Aug 21 16:25:51 CEST 2025"}
   {:content "The current time is 16:25 (4:25 PM) CEST on Thursday, August 21, 2025.",
    :role "assistant",
    :tool_calls nil,
    :function_call nil,
    :annotations []}])


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

      ;; Assistant turn. Reuse `chat-with-tools*` here. Note that we
      ;; thus keep all of the conversation history in context,
      ;; including all the tool calls and results.
      :assistant
      (let [messages (chat-with-tools* options messages)
            response (last messages)]
        (println "ASSISTANT:" (:content response))
        (recur :user messages)))))
