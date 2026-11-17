(ns cohost-media.gateway
  "The gateway: cohost-media.serve's gateway and store over the run's disk domain, on the boot
   program's ports (the control atom's :config). The boot program starts it in its own process
   (start!) — no member's death takes the network rung down, and its request threads serve the
   network members — the browser's workers — in parallel (each request materializes the value it
   serves: on one event loop they queued behind each other, and the members' leases lapsed). It
   serves atoms; it is no runtime of the job atom and runs no jobs. -main remains for running it
   standalone:

     clojure -m cohost-media.gateway <base>"
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.process :as proc]
            [cohost-media.domain :as domain]
            [cohost-media.serve :as serve]))

(defn start!
  "Start the gateway over atoms' domain and note it in the control atom. Answers serve's server map
   ({:port :stop :gateway :blobs})."
  [{:keys [control domain] :as atoms}]
  (let [cfg (:config @control)
        srv (serve/start-gateway! domain (assoc (select-keys cfg [:domain-id :api-key :store-secret])
                                                :port (:gateway-port cfg)
                                                :origin (str "http://127.0.0.1:" (:gateway-port cfg))))]
    (swap! control assoc-in [:servers :gateway] {:port (:port srv) :process "boot" :t (proc/now-ms)})
    (println (pr-str {:step :gateway :port (:port srv)}))
    srv))

(defn -main [base]
  (let [atoms (domain/open-atoms base)
        srv (start! atoms)
        control (:control atoms)]
    (loop []
      (if (:stop @control)
        (do ((:stop srv)) (println (pr-str {:step :stopped})) (shutdown-agents) (System/exit 0))
        (do (proc/sleep-ms! 500) (recur))))))
