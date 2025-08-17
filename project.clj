(defproject genko "0.1.0-SNAPSHOT"
  :description "Genko talks to language models"
  :url "https://github.com/alexei-matveev/genko"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [cheshire "6.0.0"]
                 [clj-http "3.13.0"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.babashka/sci "0.10.47"]
                 [ring/ring-jetty-adapter "1.14.1"]
                 [org.slf4j/slf4j-simple "2.0.17"]
                 [org.clojure/tools.logging "1.3.0"]
                 [ring-cors "0.1.13"]
                 [compojure "1.7.1"]]
  :main genko.main
  :aot :all
  :repl-options {:init-ns genko.main})
