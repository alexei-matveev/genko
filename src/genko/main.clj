(ns genko.main
  (:require
   [genko.core :as core]
   [genko.server :as server]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.tools.logging :as log])
  (:gen-class))


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
         response (core/chat-completion options messages)]
     (:content response))))


(comment
  (llm-apply "What is the capital of France?")
  ;; =>
  "The capital of France is Paris."

  (let [readme (slurp "README.md")
        prompt (str "Summarize the following text:\n" readme)]
    (llm-apply prompt))
  ;; =>
  "Genko is a simple command-line tool designed ...")


;; NOTE: API key may leak to stdout if OPENAI_API_KEY is set!
(defn -main [& args]
  (log/info "Entered ...")
  (let [cli-options [["-v" "--verbose" "Enable verbose mode"
                      :default false]
                     [nil "--model MODEL" "Language model"
                      :default "gpt-4.1"]
                     [nil "--base-url BASE-URL" "Base URL"
                      :default (System/getenv "OPENAI_BASE_URL")]
                     [nil "--api-key API-KEY" "API key"
                      :default (System/getenv "OPENAI_API_KEY")]
                     [nil "--server" "Start a server" :default false]
                     [nil "--port PORT" "Server port" :default 3000 :parse-fn #(Integer/parseInt %)]]
        cli-parsed (cli/parse-opts args cli-options)
        {:keys [options errors]} cli-parsed]

    (when (:verbose options)
      (pp/pprint cli-parsed))

    (cond
      (seq errors)
      (println "Errors:" (str/join \newline errors))

      (:server options)
      (server/start-server options)

      :else
      (core/chat-with-user
       options
       :user
       [{:role "developer"
         :content "You are a helpful assistant. Reply in Markdown!"}]))))
