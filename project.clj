(defproject cloudwatch "0.0.6"
  :description "Sindicati cloudwatch helper library"
  :license "Owned by CondeNast"
  :url "http://github.com/ziplist/cloudwatch"


  :release-tasks [["vcs" "assert-committed"]
                  ["clean"]
                  ["deps"]
                  ["test"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "push"]
                  ["deploy" "cnds"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]
                  ]
  :dependencies [
                 [org.clojure/clojure                  "1.7.0"]
                 [amazonica                            "0.3.22"]
                 [clj-time "0.9.0"]
                 [org.clojure/data.json "0.2.5"]
                 [clj-http "1.1.2"]
                 [com.taoensso/timbre   "3.4.0"]
                 ]

  :plugins   [[lein-environ     "1.0.0"]
              [s3-wagon-private "1.1.2" :exclusions [commons-codec commons-logging]]
             ]
  )
