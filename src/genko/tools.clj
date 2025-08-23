(ns genko.tools
  (:require
   [genko.sci :as sci]
   [cheshire.core :as json]
   [cheshire.generate :as jg]      ; encoder for hiccup.util.RawString
   [hiccup2.core :as h]
   [clojure.tools.logging :as log]))


;; Where to put it?
(jg/add-encoder hiccup.util.RawString jg/encode-str)
(comment
  (json/parse-string
   (json/generate-string
    (h/html [:p "hi!"])))
  => "<p>hi!</p>")


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
(def ^:private tool-map
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
   ;; Thus you cannot name a funciton "sci/eval-string". See Babashka
   ;; SCI [1].
   ;;
   ;; [1] https://github.com/babashka/SCI
   "sci--eval-string"
   {:tool
    (fn [arguments]
      (let [arguments (json/parse-string arguments true)
            {:keys [clojure-code]} arguments]

        ;; NOTE: Remote Code execution in its purest form here! SCI
        ;; ist somewhat better than a plain (eval (read-string ...))
        ;; but it still fits the definition of RCE!
        (let [value (sci/eval-string clojure-code)
              text-value (pr-str value)]
          (log/warn "sci--eval-string:" clojure-code "=>" text-value)
          ;; NOTE: eventually shorten the `clojure-code` in text in
          ;; case it is too long? It is anyway present in the context
          ;; as `arguments`!
          ;;
          ;; Additional context as HTML string or as markdown. We
          ;; assume encoder for `RawString` has been
          ;; configured. Otherwise you need an extra `str` or will
          ;; get "Cannot JSON encode object of class: class
          ;; hiccup.util.RawString". HTML tag `<thinking>` is handled
          ;; specially by Open WebUI. In fact it is rendered as
          ;; `<details>`!
          (if false
            (h/html
                [:thinking              ; details?
                 [:summary "Additional context"]
                 [:p "Clojure code " [:clojure clojure-code] " evaluates to " [:value text-value]]
                 [:p "Cite this but only when asked how you computed the value!"]])
            (h/html
                [:thinking
                 (str
                  "#### Additional Context\n\n"
                  "Clojure code\n```clojure\n" clojure-code "\n```\nevaluates to\n\n" text-value
                  "\n\nCite this but only when asked how you computed the value!")])))))

    :schema {:description "Evaluate Clojure code in restricted
    interpreter. Mostly for simple arithmetics. Use BigInts to avoid
    overflow."
             :parameters {:type "object"
                          :properties {:clojure-code
                                       {:type "string"
                                        :description "Clojure code consisting of arithmetic expressions."}}
                          :required ["clojure-code"]
                          :additionalProperties false}
             :strict true}}})


;; Convert global `tool-map` to pass to LLM.
(defn declare-tools []
  (for [[fn-name fn-map] tool-map
        :let [schema (:schema fn-map)]]
    {:type "function"
     :function (assoc schema :name fn-name)}))


;; Call tools available in the global `tool-map`. If we ever have a
;; luxus problem of having too many tool calls, one could even call
;; them in parallel here!
(defn call-tools [tool-calls]
  (for [tool-call tool-calls
        :let [{:keys [id function]} tool-call
              {:keys [name arguments]} function
              tool (:tool (tool-map name))]]

    ;; Value might be to long a text to log every tyme. In fact
    ;; `arguments` ist also potentially unbondend string from
    ;; untrusted source!
    (do
      (log/info "tool call" name ":" arguments)

      (let [value (try
                    (tool arguments)
                    (catch Exception e
                      (.getMessage e)))]
        {:role "tool"
         :name name
         :tool_call_id id
         :content value}))))
