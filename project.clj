(defproject genko "0.1.0-SNAPSHOT"
  :description "Genko talks to language models"
  :url "https://github.com/alexei-matveev/genko"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [cheshire "6.0.0"]
                 [clj-http "3.13.0"]]
  :main genko.core
  :aot :all
  :repl-options {:init-ns genko.core})
