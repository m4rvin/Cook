(ns cook.sim.runner
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [clj-time.core :as t]
            [cheshire.core :as json]
            [datomic.api :as d]
            [simulant.sim :as sim]
            [simulant.util :as simu]
            [cook.sim.database :as db]
            [cook.sim.database :as data]))

(defmethod sim/create-sim :test.type/cook
  [sim-conn test sim]
  (-> @(d/transact sim-conn (sim/construct-basic-sim test sim))
      (simu/tx-ent (:db/id sim))))

(defn schedule-cook-job
  "Given the location of the Cook API, a job Id, and a map of job characteristics,
  schedules a job with the specified characteristics via the Cook API.
  Note:  two of the characteristics, :exit-code and :duration, combine to form the
  actual executable command of the job - simply a shell command that will consume
  no actual resources, but will run for the specified number of seconds before exiting
  with the specified code."
  [cook-uri job-id {name :job/name
                    username :job/username
                    priority :job/priority
                    duration :job/duration
                    mem :job/memory
                    cpu :job/cpu
                    exit-code :job/exit-code}]
  (let [cmd (str "sleep " duration "; exit " exit-code)
        ;; TODO: determine if max_retries and max_runtime need to be configured,
        ;; or even randomized per job?
        body (json/generate-string {:jobs [{:name name
                                            :priority priority
                                            :max_retries 3
                                            :max_runtime 86400000
                                            :mem mem
                                            :cpus cpu
                                            :uuid job-id
                                            :command cmd}]})]
    (println "scheduling cook job with payload: " body)
    (http/post (str cook-uri "/rawscheduler")
               {:body body
                :content-type :json
                :basic-auth [username]})))

(defmethod sim/perform-action :action.type/job
  [action process]
  (let [sim (-> process :sim/_processes simu/only)
        action-log (simu/getx sim/*services* :simulant.sim/actionLog)
        before-nano (System/nanoTime)
        before-millis (System/currentTimeMillis)
        job-uuid (d/squuid)]
    (schedule-cook-job (:sim/systemURI sim) job-uuid action)
    ;; TODO: record our own timestamp manually rather than relying on
    ;; datomic transactions?
    (action-log [{:actionLog/nsec (- (System/nanoTime) before-nano)
                  :db/id (d/tempid :db.part/user)
                  :actionLog/sim (simu/e sim)
                  :actionLog/action (simu/e action)
                  :job/requested-at before-millis
                  :job/uuid job-uuid}])))

(defn simulate!
  "Top level function that runs all of the jobs in a Simulation at their specified
  times.
  Parameters:  system components for the app settings and for the simulation
  database, the ID of the simulation, and a label for this specific sim run
  (to identify this sim run in charts and generally provide a way to identify it
  later)."
  [settings sim-db test-id label]
  (println "Running a simulation for schedule " test-id "...")
  (let [{:keys [sim-db-uri cook-api-uri process-count]} settings
        conn (:conn sim-db)
        cook-sim (sim/create-sim conn
                                 (-> (d/db conn) (d/entity test-id))
                                 {:db/id  (d/tempid :sim)
                                  :sim/systemURI cook-api-uri
                                  :sim/label label
                                  :sim/processCount process-count
                                  :source/codebase (simu/gen-codebase)})
        sim-id (:db/id cook-sim)]
    (println "Created simulation " sim-id ".")
    (println "Label is \"" label "\".")
    (println "After jobs are finished, try running report -e " sim-id ".")
    (sim/create-action-log conn cook-sim)
    ;; Since this test relies on real cook, we can never configure the clock
    ;; to run at any speed other than 1x
    (sim/create-fixed-clock conn cook-sim {:clock/multiplier 1})
    (time
     (mapv (fn [proc] @(:runner proc))
           (into [] (repeatedly (:sim/processCount cook-sim)
                                #(sim/run-sim-process sim-db-uri sim-id)))))
    sim-id))
