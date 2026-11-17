;; cohost-media's boot program (stock Clojure on the JVM, the run's only main): it creates the disk
;; domain and the window, and starts every runtime as a POOL — the JVM member on a cohost.thread host
;; executor in this process, the other runtimes as process-backed pools on their native seats, each
;; member handed its pool config (caps, pool sizes, options) as data. The boot program watches the
;; pools itself (cohost-media.supervise's bookkeeping; no supervisor process), starts the gateway in
;; process, writes the library, and checks the outputs. The browser stays a process on the network
;; rung — the one member that is a pool of no runtime.
;;
;;   bb cohost-media demo [--hosts bb,jvm,...] [--kill transcode|coordinator]   (from eve/)
;;   bb cohost-media scan <dir> [--hosts ...]     the library of a folder of your own
;;   bb cohost-media verify                        the done bar: the runs of doc README §"The done bar", compared
;;
;; A run's directory (~/.cohost-media/runs/<stamp>) holds media/ (the demo's generated files), out/ (transcodes,
;; thumbnails, contact sheets), d (the disk domain's files), logs/ (each member's), report.edn. The window is
;; /dev/shm/cohost-media/<stamp> (decoded PCM; the system's temporary directory where there is no /dev/shm, as on macOS). The boot program reads and writes the domain's atoms directly; the members join eve.thread.
(ns cohost-media.boot
  (:refer-clojure :exclude [swap!])
  (:require [cohost.hosts.auto]
            [cohost.thread :as thread]
            [cohost-media.boot-fs :as fs]
            [cohost-media.boot-proc :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [cohost-media.core :as em]
            [cohost-media.domain :as domain]
            [cohost-media.gateway :as gateway]
            [cohost-media.member :as member]
            [cohost-media.supervise :as sv])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.util.concurrent CompletableFuture CompletionStage]))

(def eve-dir (str (fs/absolutize ".")))
(def app-dir (str (fs/path eve-dir "capstone" "cohost-media")))
(def hosts-edn (edn/read-string (slurp (str (fs/path eve-dir "hosts.edn")))))

(defn now [] (System/currentTimeMillis))
(defn log [& xs] (println (str "[cohost-media " (java.time.LocalTime/now) "]") (str/join " " xs)) (flush))

(defn roots [host & more]
  (str/join ":" (concat [(str (fs/path eve-dir "src"))]
                        (map #(str (fs/path eve-dir %)) (get hosts-edn host))
                        [(str (fs/path app-dir "src"))]
                        (map #(str (fs/path app-dir %)) more))))

(defn find-tools
  "The runtimes no package manager installs whole — jolt and its Chez Scheme — and the page's browser: on PATH, else
   where eve's tool boxes put them (/opt/eve-tools)."
  []
  (let [which (fn [n & fallbacks] (or (some-> (fs/which n) str) (some #(when (fs/executable? %) %) fallbacks) (last fallbacks)))]
    {:jolt (which "jolt" "/opt/eve-tools/jolt/bin/jolt")
     ;; a jolt run from its source tree needs a Chez Scheme (JOLT_CHEZ); a release build (Homebrew's) carries its own
     :jolt-chez (or (System/getenv "JOLT_CHEZ")
                    (when (fs/executable? "/opt/eve-tools/chez/bin/scheme") "/opt/eve-tools/chez/bin/scheme"))
     ;; EVE_CHROME, else the first Chromium or Chrome found: Playwright's on this tree's Linux boxes, macOS's apps, PATH
     :chrome (or (System/getenv "EVE_CHROME")
                 (some (fn [c] (when (fs/executable? c) c))
                       ["/opt/pw-browsers/chromium"
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                        "/Applications/Chromium.app/Contents/MacOS/Chromium"])
                 (some #(some-> (fs/which %) str) ["chromium" "chromium-browser" "google-chrome" "google-chrome-stable"])
                 "chromium")
     :path (str "/opt/eve-tools/jolt/bin:/opt/eve-tools/chez/bin:"
                (System/getenv "PATH"))}))

(def tools (find-tools))

(defn write!
  "Atom k (:control or :library) of the run swapped by (apply f value args), on the disk: the boot
   program is a JVM writer of the domain, the same class as the JVM member — the old launcher, a
   babashka process, wrote through the gateway once members ran because babashka must not write a
   shared disk domain concurrently; nothing here has that constraint."
  [atoms k f & args]
  (swap! (get atoms k) (fn [v] (apply f v args)))
  nil)

(def pool-configs
  "Each member's pool config: cohost-media.member/pool-configs (the one table, in the namespace every
   runtime loads; the boot program hands each pool its entry as data)."
  member/pool-configs)

(defn member-cmd
  "The command line starting member rid of host h on domain base (out, window) as a process-backed
   pool on its native seat; cfg the run's (control :config). Every member runs the same shared entry
   (cohost-media.member's -main) with its pool config as an EDN argument; a babashka member is also
   given the gateway's URL and the run's api key (its ctx records them), the browser the page's URL.
   The JVM member has no command line: it runs in this process (start-pools!)."
  [h rid base out window cfg]
  (let [args [base out window rid]
        config (pr-str (get pool-configs h))]
    (case h
      :bb
      {:cmd (concat ["bb"
                     "-cp"
                     (roots :bb)
                     "-m"
                     "cohost-media.member"]
                    args
                    [config
                     (str "http://127.0.0.1:" (:gateway-port cfg) "/gw")
                     (:api-key cfg)])}
      :node
      {:cmd (concat ["node" (str (fs/path app-dir "target" "node-member.js"))]
                    args
                    [config])
       :dir app-dir}
      :glj
      {:cmd (concat [(str (fs/path app-dir "target" "glj-cohost-media" "glj-eve"))
                     "-e"
                     (str "(require 'eve.host-completion) (require 'cohost-media.member) "
                          "(apply cohost-media.member/-main *command-line-args*)")]
                    args
                    [config])
       :env {"GLJ_CLASSPATH" (roots :glj)}}
      :jolt
      {:cmd (concat [(:jolt tools)
                     "-Scp"
                     (roots :jolt)
                     "-m"
                     "cohost-media.member"]
                    args
                    [config])
       :env (cond-> {"PATH" (:path tools)} (:jolt-chez tools) (assoc "JOLT_CHEZ" (:jolt-chez tools)))}
      ;; the browser: Chromium on the page the boot program serves (its runtime joins over the network rung through the
      ;; boot program's proxy; ?check= the done bar's re-queue row), a fresh profile each start — a window of its own
      ;; where the run shows the page (:show-page), else headless
      :browser
      (let [url (str "http://127.0.0.1:" (:page-port cfg) "/index.html?check=f01:thumb")
            profile (str "--user-data-dir=" (fs/create-temp-dir {:prefix "cohost-media-chrome-"}))]
        {:cmd (if (:show-page cfg)
                (concat [(:chrome tools) "--no-first-run" "--no-default-browser-check" "--no-proxy-server"
                         "--enable-logging=stderr" "--v=0" "--window-size=1400,1000" profile]
                        ;; Chromium refuses to run as root with its sandbox
                        (when (= "root" (System/getProperty "user.name")) ["--no-sandbox"])
                        [(str "--app=" url)])
                [(:chrome tools) "--headless=new" "--no-sandbox" "--disable-gpu" "--no-proxy-server" "--no-first-run"
                 "--enable-logging=stderr" "--v=0" profile url])}))))

(def default-hosts
  "A run's runtimes of the job atom: babashka (the index), the JVM (metadata, hashes, loudness — and the coordinator),
   Node (thumbnails, contact sheets, tags), glojure (the media server, the folder watcher), jolt (the transcoder and the
   PCM decoder, FFmpeg's libraries through its FFI) and the browser (waveforms; the page). Without jolt the JVM
   transcodes by the ffmpeg CLI."
  [:bb :jvm :node :glj :jolt :browser])

(defn child-specs
  "The run's children, as the boot program's watch takes them (cohost-media.supervise): the gateway; a member per host
   of hosts — babashka and the browser once the gateway is up: the page's first act is a gateway call, and its workers
   die on the proxy's 503 if they make it before the gateway answers; the boot program serves the page itself from
   the run's first second and the page watches the members join once it starts. The gateway and the JVM member run
   in this process: their :cmd names what runs, for the record."
  [run cfg hosts]
  (let [logs (str (fs/path (:dir run) "logs"))]
    (into {"gateway" {:host :gateway :member? false :wait [] :logs logs :dir eve-dir :env {}
                      :cmd ["in-process" "cohost-media.gateway"]}}
          (map (fn [h]
                 (let [rid (name h)]
                   (if (= :jvm h)
                     [rid {:host h :member? true :wait [] :logs logs :dir eve-dir :env {}
                           :cmd ["in-process" "cohost-media.member"]}]
                     (let [{:keys [cmd env dir]} (member-cmd h rid (:base run) (:out run) (:window run) cfg)]
                       [rid {:host h :member? true :cmd (mapv str cmd) :env (or env {}) :dir (str (or dir eve-dir))
                             :logs logs :wait (case h (:bb :browser) [:gateway] [])}])))))
          hosts)))

;; ---------------------------------------------------------------------------
;; The demo's media: ten files from ffmpeg's lavfi sources, two seeded duplicate pairs, no download
;; ---------------------------------------------------------------------------

(defn ffmpeg! [& args]
  (let [{:keys [exit err]} (apply p/shell {:continue true :out :string :err :string} "ffmpeg" "-nostdin" "-v" "error" "-y" args)]
    (when-not (zero? exit) (throw (ex-info (str "ffmpeg failed: " err) {:args args})))))

(def encoders
  "The encoders the ffmpeg on PATH has (ffmpeg -encoders): builds differ — Homebrew's has no libvorbis."
  (delay (->> (str/split-lines (:out (p/shell {:out :string :err :string} "ffmpeg" "-hide_banner" "-encoders")))
              (keep #(second (re-find #"^ [VAS][.A-Z]{5} (\S+)" %)))
              set)))

(defn generate-demo! [dir]
  (fs/create-dirs dir)
  (when-not (contains? @encoders "libx264")
    (throw (ex-info (str "cohost-media: the ffmpeg on PATH has no libx264 encoder; the demo and every transcode are H.264 "
                         "(an ffmpeg built with libx264: Homebrew's, a distribution's ffmpeg package)") {})))
  (let [f (fn [n] (str (fs/path dir n)))
        ;; Vorbis by libvorbis where the build has it, else ffmpeg's own encoder (experimental, stereo only)
        vorbis (if (contains? @encoders "libvorbis") ["-c:a" "libvorbis"] ["-c:a" "vorbis" "-strict" "-2" "-ac" "2"])]
    (ffmpeg! "-f" "lavfi" "-i" "testsrc=size=1280x720:rate=25:duration=10" "-f" "lavfi" "-i" "sine=frequency=440:duration=10"
             "-c:v" "libx264" "-preset" "ultrafast" "-pix_fmt" "yuv420p" "-c:a" "aac" "-shortest" (f "f01-testsrc.mp4"))
    ;; a seeded duplicate: f01 resized and re-encoded into another container
    (apply ffmpeg! (concat ["-i" (f "f01-testsrc.mp4") "-vf" "scale=640:360" "-c:v" "mpeg4" "-q:v" "4"] vorbis
                           [(f "f02-testsrc-copy.mkv")]))
    (ffmpeg! "-f" "lavfi" "-i" "smptebars=size=1280x720:rate=25:duration=10" "-f" "lavfi" "-i" "sine=frequency=1000:duration=10"
             "-c:v" "mpeg4" "-q:v" "4" "-c:a" "aac" "-shortest" (f "f03-bars.mov"))
    (ffmpeg! "-f" "lavfi" "-i" "mandelbrot=size=1280x720:rate=25" "-t" "10" "-c:v" "mpeg4" "-q:v" "4" (f "f04-mandelbrot.mkv"))
    (ffmpeg! "-f" "lavfi" "-i" "testsrc2=size=1280x720:rate=25:duration=10" "-f" "lavfi"
             "-i" "anoisesrc=color=pink:duration=10:amplitude=0.25:seed=3" "-c:v" "mpeg4" "-q:v" "5" "-c:a" "pcm_s16le"
             "-shortest" (f "f05-noise.avi"))
    (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=440:duration=10:sample_rate=48000" "-ac" "2" (f "f06-tone.wav"))
    (ffmpeg! "-f" "lavfi" "-i" "anoisesrc=color=pink:duration=10:amplitude=0.4:seed=5" "-ac" "2" "-c:a" "flac" (f "f07-pink.flac"))
    ;; an MP3 by libmp3lame; a build without it (ffmpeg has no MP3 encoder of its own) writes the tone as Ogg Vorbis
    (if (contains? @encoders "libmp3lame")
      (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=220:duration=10,volume=0.5" "-ac" "2" "-c:a" "libmp3lame" "-b:a" "128k"
               (f "f08-low.mp3"))
      (apply ffmpeg! (concat ["-f" "lavfi" "-i" "sine=frequency=220:duration=10,volume=0.5" "-ac" "2"] vorbis
                             [(f "f08-low.ogg")])))
    (ffmpeg! "-f" "lavfi" "-i" "gradients=size=1280x720:seed=7:nb_colors=4" "-frames:v" "1" (f "f09-gradient.png"))
    ;; the second seeded duplicate: f09 resized, as a JPEG
    (ffmpeg! "-i" (f "f09-gradient.png") "-vf" "scale=640:360" "-q:v" "3" (f "f10-gradient-small.jpg"))
    (sort (map str (fs/list-dir dir)))))

;; ---------------------------------------------------------------------------
;; The library: every media file of a folder, written as :new (the coordinator plans each)
;; ---------------------------------------------------------------------------

(defn scan!
  "Every media file of dir written into the library as :new (cohost-media.core/add-files, one swap): the boot program's scan,
   when no Go member watches the folder."
  [atoms dir]
  (let [files (->> (fs/list-dir dir)
                   (map str)
                   (filter em/media-kind)
                   (map (fn [p]
                          {:path p
                           :size (fs/size p)
                           :mtime (str (fs/last-modified-time p))})))]
    (write! atoms :library em/add-files files "launcher")
    (count files)))

;; ---------------------------------------------------------------------------
;; The pools and their watch — what the supervisor processes used to do, in the
;; boot program: the gateway and the JVM member run in this process (the
;; gateway a server thread, the JVM member a task on a cohost.thread host
;; executor); the other members are process-backed pools on their native
;; seats, started and restarted by watch threads here. All of it is recorded
;; in the control atom through cohost-media.supervise, as the supervisors
;; recorded it, so the checks read the same bookkeeping.
;; ---------------------------------------------------------------------------

(defn- spawn-child!
  "Start process child rid once (its next incarnation): through /bin/sh -c supervise's script, so the
   child's OS process is the program's own and its output lands in its log files; a waiter thread
   records the exit and starts it again per the restart policy (a clean exit is a stop, not a restart)."
  [atoms procs rid child]
  (let [ctl (:control atoms)
        n (sv/next-start ctl rid)
        logf (sv/log-path child rid n)
        env (merge {"PATH" (System/getenv "PATH")} (:env child)
                   {"EVE_LOG" logf "EVE_ERR" (str logf ".err")})
        pr (apply p/process {:out :string :err :string :extra-env env :dir (:dir child)}
                  "/bin/sh" "-c" (sv/sh-script) (:cmd child))]
    (sv/started! ctl rid child (.pid ^Process (.-proc pr)) n (now))
    (clojure.core/swap! procs assoc rid pr)
    (doto (Thread. (fn []
                     (let [code (:exit (deref pr))]
                       (sv/exited! ctl rid code (now))
                       (when (and (not (sv/stopping? ctl)) (not (zero? code)))
                         (if (sv/restart? (:exits (get (:members (sv/value ctl)) rid)) (now))
                           (spawn-child! atoms procs rid child)
                           (sv/gave-up! ctl rid (now)))))))
      (.setDaemon true)
      (.start))
    pr))

(defn- start-child!
  "Start process child rid when the servers it waits for are up (control :servers)."
  [atoms procs rid child]
  (doto (Thread. (fn []
                   (loop []
                     (cond (sv/stopping? (:control atoms)) nil
                           (sv/ready? (:control atoms) child) (spawn-child! atoms procs rid child)
                           :else (do (Thread/sleep 200) (recur))))))
    (.setDaemon true)
    (.start)))

(defn- watch-jvm-member!
  "The JVM member's pool: its serve! on the host executor ex, watched — an error exit is recorded and
   the member is submitted again per the restart policy; a clean exit (the run's :stop) ends the watch."
  [run atoms cfg ex procs]
  (doto (Thread.
         (fn []
           (loop []
             (let [ctl (:control atoms)
                   rid "jvm"
                   child (get (:children (sv/value ctl)) rid)
                   n (sv/next-start ctl rid)]
               (sv/started! ctl rid child (.pid (java.lang.ProcessHandle/current)) n (now))
               (let [fut (thread/submit-future
                          ex (fn [] (member/attempt
                                     (fn [] (member/serve! (:base run) (:out run) (:window run) rid
                                                           (str "http://127.0.0.1:" (:gateway-port cfg) "/gw")
                                                           (:api-key cfg) (:jvm pool-configs))))))
                     _ (clojure.core/swap! procs assoc rid {:in-process true :future fut})
                     [code err] (try (let [[ok? e] (thread/deref-future fut)] [(if ok? 0 1) e])
                                     (catch Throwable t [1 t]))]
                 (when-not (zero? code)
                   (println (pr-str {:step :jvm-member-error :rid rid :error (str err)}))
                   (when (instance? Throwable err) (.printStackTrace ^Throwable err)))
                 (sv/exited! ctl rid code (now))
                 (when (and (not (sv/stopping? ctl)) (not (zero? code)))
                   (if (sv/restart? (:exits (get (:members (sv/value ctl)) rid)) (now))
                     (recur)
                     (sv/gave-up! ctl rid (now)))))))))
    (.setDaemon true)
    (.start)))

(defn- watch-asks!
  "The coordinator's restart asks (a member whose lease lapsed while its process lives): kill the
   process child's pid; its waiter starts it again. An in-process member has no pid to kill — the
   JVM member's own watch restarts it when its serve! ends."
  [atoms procs]
  (doto (Thread. (fn []
                   (loop []
                     (when-not (sv/stopping? (:control atoms))
                       (doseq [[rid pid] (sv/asked (:control atoms))]
                         (sv/restarting! (:control atoms) rid (now))
                         (when (and pid (instance? cohost_media.boot_proc.Proc (get @procs rid)))
                           (p/shell {:continue true} "kill" "-9" (str pid))))
                       (Thread/sleep 300)
                       (recur)))))
    (.setDaemon true)
    (.start)))

(defn start-pools!
  "Start the run's pools: the supervisor note (the boot program itself, host :boot), the gateway in
   this process, the JVM member's executor pool when a JVM member runs, and a watched process pool
   per other member; then the restart-ask watch."
  [run atoms cfg hosts procs]
  (let [ctl (:control atoms)]
    (sv/up! ctl :boot (.pid (java.lang.ProcessHandle/current)) (now))
    (clojure.core/swap! procs assoc "gateway" (gateway/start! atoms))
    (when (some #{:jvm} hosts)
      (let [ex (thread/new-executor :pooled 1)]
        (clojure.core/swap! procs assoc "executor" ex)
        (watch-jvm-member! run atoms cfg ex procs)))
    (doseq [h hosts
            :when (contains? #{:bb :node :glj :jolt :browser} h)]
      (let [rid (name h)
            child (get (:children (sv/value ctl)) rid)]
        (when child (start-child! atoms procs rid child))))
    (watch-asks! atoms procs)))

(defn wait-gateway!
  "Wait for the gateway's note ([:servers :gateway] in the control atom) before any gateway I/O. The
   gateway starts in this process, before the members — the wait is the same readiness the watch
   honors before starting the bb member, kept so a slow start is reported, not raced."
  [atoms timeout-ms]
  (let [t0 (now)]
    (loop [waited 0]
      (cond (contains? (:servers @(:control atoms)) :gateway) true
            (> (- (now) t0) timeout-ms)
            (throw (ex-info (str "cohost-media: the gateway did not come up within "
                                 (quot timeout-ms 1000) "s")
                            {:unready true}))
            :else (do (when (and (pos? waited) (zero? (mod waited 30000)))
                        (log "the gateway is still starting (" (quot waited 1000) "s)"))
                      (Thread/sleep 300) (recur (+ waited 300)))))))

(defn alive? [pid] (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid)))))

(defn kill-9! [pid] (p/shell {:continue true} "kill" "-9" (str pid)))

;; ---------------------------------------------------------------------------
;; A run
;; ---------------------------------------------------------------------------

(defn new-run [opts]
  (let [stamp (str (java.time.LocalDateTime/now))
        stamp (str/replace (subs stamp 0 (min 23 (count stamp))) #"[:.]" "-")
        dir (str (fs/path (fs/home) ".cohost-media" "runs" stamp))
        window (str (fs/path (if (fs/directory? "/dev/shm") "/dev/shm" (fs/temp-dir)) "cohost-media" stamp))]
    (fs/create-dirs (fs/path dir "logs"))
    (fs/create-dirs (fs/path dir "out"))
    (fs/create-dirs window)
    {:dir dir :base (str (fs/path dir "d")) :out (str (fs/path dir "out")) :window window :opts opts}))

(defn descs [atoms] (:descs (domain/meta-value atoms)))

(defn complete? [{:keys [library index]}]
  (let [files (:files @library) ix @index]
    (and (seq files)
         (every? (fn [[_ e]] (contains? #{:done :error} (:status e))) files)
         (:complete? ix)
         (= (count files) (get-in ix [:job :generation])))))

(defn kill-step!
  "The kill tests: kill -9 of a member — :kill \"transcode\": the one running a transcode, once it has run a second;
   :kill \"coordinator\": the coordinator, once a file is done and another still planned. Its pool's watch starts it
   again. A member running in the boot process (the JVM member) has no pid of its own to kill: the step names it
   and skips — in a run whose coordinator is a process member (no JVM member: babashka coordinates) it kills as before."
  [run atoms procs state]
  (when (and (= "coordinator" (get-in run [:opts :kill])) (not (:killed @state)))
    (let [statuses (map :status (vals (:files @(:library atoms))))
          ctl @(:control atoms)
          rid (get-in ctl [:config :coordinator])
          pid (get-in ctl [:members rid :pid])]
      (when (and pid (some #{:done} statuses) (some #{:planned} statuses))
        (if (instance? cohost_media.boot_proc.Proc (get @procs rid))
          (do (log "kill -9 the coordinator" rid "pid" pid)
              (kill-9! pid)
              (clojure.core/swap! state assoc :killed {:rid rid :pid pid :coordinator true :at (now)}))
          (do (log "kill step: the coordinator" rid "runs in the boot process — no pid to kill; skipped")
              (clojure.core/swap! state assoc :kill-skipped {:rid rid :reason :in-process}))))))
  (when (and (= "transcode" (get-in run [:opts :kill])) (not (:killed @state)))
    (let [running (filter (fn [[_ d]] (and (= :running (:state d))
                                           (= (em/task-sym :transcode) (get-in d [:work :task]))
                                           (> (- (now) (:claimed d 0)) 1000)))
                          (descs atoms))]
      (when-let [[id d] (first running)]
        (let [rid (first (str/split (:owner d) #"#"))
              pid (get-in @(:control atoms) [:members rid :pid])]
          (when pid
            (if (instance? cohost_media.boot_proc.Proc (get @procs rid))
              (do (log "kill -9" rid "pid" pid "mid-transcode of" id (get-in d [:work :args 0 :id]))
                  (kill-9! pid)
                  (clojure.core/swap! state assoc :killed {:rid rid :pid pid :descriptor id :file (get-in d [:work :args 0 :id])
                                              :at (now)}))
              (do (log "kill step: the transcode runs on" rid "in the boot process — no pid to kill; skipped")
                  (clojure.core/swap! state assoc :kill-skipped {:rid rid :reason :in-process})))))))))

(defn- unclaimable-summary
  "The library's deferred jobs (the coordinator's :deferred entries: planned, but no joined runtime's caps
   cover them), named by kind, count and the caps they are missing:
   \"unclaimable: 4× :thumb (needs #{:npm}), 2× :publish (needs #{:serve})\". Nil when nothing is deferred —
   the shapes that cover the corpus print no such line."
  [files]
  (let [groups (reduce (fn [g [_ e]]
                         (reduce (fn [g [k info]] (update g [k (:missing info)] (fnil inc 0)))
                                 g (:deferred e)))
                       {} files)]
    (when (seq groups)
      (str "unclaimable: "
           (str/join ", " (map (fn [[[k missing] n]]
                                 (str n "× " k " (needs " (pr-str (into (sorted-set) missing)) ")"))
                               (sort-by (fn [[[k _] _]] (name k)) groups)))))))

(def stall-abort-ms
  "A run that shows no sign of life — no file status change, no control-atom
   change (member joins, leases), no jobs-atom change (member heartbeats tick
   there while any member works, however long its current job runs) — for this
   long is dead, not slow: the wait aborts and the run reports and stops its
   members instead of polling to the full timeout at full CPU."
  90000)

(defn- pointer-sig
  "The four run atoms' root pointers (eve.atom/version's second word — two i32 loads each): a member's
   commit to any of them moves its pointer, so an unchanged signature means none of their values changed.
   The full check below runs on a moved pointer, and at least every 5 s for the progress print regardless."
  [atoms]
  [(second (ea/version (:jobs atoms))) (second (ea/version (:control atoms)))
   (second (ea/version (:library atoms))) (second (ea/version (:index atoms)))])

(defn wait-complete! [run atoms procs state timeout-ms]
  (let [t0 (now)]
    (loop [last-print 0 last-sig nil last-change (now) last-psig nil last-full 0]
      (let [t (now)
            psig (pointer-sig atoms)]
        (cond
          (> (- t t0) timeout-ms) false
          ;; No member wrote any run atom since the last full check and the print is not due: the
          ;; pointer poll above is all this iteration costs. (The full check used to run every
          ;; 300 ms — three eve-atom derefs and two value hashes; on an interpreted host a single
          ;; deref of a tiny value measures 102–272 ms, so the cycle outlasted its own sleep and
          ;; the wait pinned a core for the whole run, idle or not.)
          (and (= psig last-psig) (< (- t last-full) 5000))
          (do (Thread/sleep 300)
              (recur last-print last-sig last-change psig last-full))
          (complete? atoms) true
          :else
          (do (kill-step! run atoms procs state)
              (let [ctl @(:control atoms)
                    sig [(frequencies (map (comp :status val) (:files @(:library atoms))))
                         (hash ctl)
                         (hash @(:jobs atoms))]
                    changed? (not= sig last-sig)
                    last-change (if changed? (now) last-change)]
                (if (and (not changed?) (> (- (now) last-change) stall-abort-ms))
                  (do (log "no progress for" (quot stall-abort-ms 1000)
                           "s (no file status, control or jobs change) — aborting the wait")
                      false)
                  (let [lp (if (> (- (now) last-print) 5000)
                             (let [files (:files @(:library atoms))
                                   head ["progress" (first sig)
                                         "coordinator" (get-in ctl [:config :coordinator])
                                         "supervisor" (some-> (get-in ctl [:supervisor :host]) name)]
                                   u (unclaimable-summary files)]
                               (apply log (if u (conj head u) head))
                               (now))
                             last-print)]
                    (Thread/sleep 300)
                    (recur lp sig last-change psig (now)))))))))))

(defn stop-members!
  "The run's stop written; the members end (their serve loops see :stop; the browser is ended by its
   watch), the gateway stops, the executor shuts down — or, after 40 s, what is left is killed."
  [atoms procs]
  (try
    (write! atoms :control assoc :stop true)
    (catch Exception e
      (log "the stop could not be written (" (ex-message e) "); the pools are killed")))
  (let [t0 (now)]
    (loop []
      (when (and (< (- (now) t0) 40000)
                 (or (some (fn [[rid v]] (and (not= "browser" rid)
                                              (instance? cohost_media.boot_proc.Proc v) (p/alive? v))) @procs)
                     (and (contains? @procs "jvm")
                          (not= :stopped (get-in @(:control atoms) [:members "jvm" :state])))))
        (Thread/sleep 300)
        (recur))))
  (doseq [[_ v] @procs
          :when (instance? cohost_media.boot_proc.Proc v)]
    (p/destroy-tree v))
  (when-let [srv (get @procs "gateway")]
    (try ((:stop srv)) (catch Throwable _ nil)))
  (when-let [ex (get @procs "executor")]
    (try (thread/shutdown-executor! ex) (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; The checks (P§8.6 as amended)
;; ---------------------------------------------------------------------------

(defn sh-out [& args] (:out (apply p/shell {:continue true :out :string :err :string} args)))

(defn probe-codecs [path]
  (set (str/split-lines (str/trim (sh-out "ffprobe" "-v" "error" "-show_entries" "stream=codec_name" "-of" "csv=p=0" path)))))

(defn image-dims [path]
  (let [[w h] (str/split (str/trim (sh-out "ffprobe" "-v" "error" "-select_streams" "v:0" "-show_entries"
                                           "stream=width,height" "-of" "csv=p=0:s=x" path)) #"x")]
    (when (and w h) [(parse-long w) (parse-long h)])))

(defn ffmpeg-lufs [path]
  (let [err (:err (p/shell {:continue true :out :string :err :string} "ffmpeg" "-nostdin" "-nostats" "-i" path
                           "-map" "0:a:0" "-af" "ebur128" "-f" "null" "-"))
        i (last (re-seq #"I:\s+(-?[0-9.]+) LUFS" err))]
    (some-> i second parse-double)))

(defn sha256
  "A file's SHA-256, hex (in process: macOS has no sha256sum)."
  [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (java.io.FileInputStream. (str path))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn rid-of [owner] (when owner (first (str/split owner #"#"))))

(defn check [run atoms state]
  (let [files (:files @(:library atoms))
        ix @(:index atoms)
        meta (domain/meta-value atoms)
        by-id (into {} (map (fn [[_ e]] [(:id e) e]) files))
        transcodes (keep (fn [[_ e]] (when-let [t (get-in e [:results :transcode :path])] [(:id e) (:kind e) t])) files)
        thumbs (keep (fn [[_ e]] (when-let [t (get-in e [:results :thumb :path])] [(:id e) t])) files)
        sheets (keep (fn [[_ e]] (when-let [t (get-in e [:results :sheet :path])] [(:id e) t])) files)
        loud (keep (fn [[p e]] (when-let [l (get-in e [:results :loudness :lufs])] [(:id e) l (ffmpeg-lufs p)])) files)
        ran-on (set (concat (mapcat (fn [[_ e]] (map rid-of (vals (:ran-on e)))) files)
                            (map rid-of (vals (:ran-on ix)))))
        ctl @(:control atoms)
        started (set (keep (fn [[rid m]] (when (:member? m) rid)) (:members ctl)))
        children (set (keys (:children ctl)))
        registry (set (keys (:runtimes meta)))
        mp4 (mapv (fn [[id k t]] (let [c (probe-codecs t)]
                                   {:id id :codecs c :ok (if (= :audio k) (= #{"aac"} c) (and (contains? c "h264") (or (= c #{"h264"}) (contains? c "aac"))))}))
                  transcodes)
        dims (mapv (fn [[id t]] (let [[w _ :as d] (image-dims t)] {:id id :dims d :ok (= em/thumb-width w)})) thumbs)
        sheet-dims [(* em/sheet-cols em/sheet-tile-width) (* em/sheet-rows em/sheet-tile-height)]
        sdims (mapv (fn [[id t]] (let [d (image-dims t)] {:id id :dims d :ok (= sheet-dims d)})) sheets)
        louds (mapv (fn [[id ours theirs]] {:id id :cohost-media ours :ffmpeg theirs
                                            :ok (and theirs (<= (Math/abs (- ours theirs)) 0.5))}) loud)
        killed (:killed @state)
        starts (get-in ctl [:members (:rid killed) :incarnation] 0)
        kill-row (when killed
                   (if (:coordinator killed)
                     {:killed (:rid killed) :coordinator true :starts starts :ok (>= starts 2)}
                     (let [e (get by-id (:file killed))]
                       {:file (:file killed) :killed (:rid killed) :attempts (get-in e [:attempts :transcode])
                        :starts starts :sha256 (some-> (get-in e [:results :transcode :path]) sha256)
                        :ok (and (= 2 (get-in e [:attempts :transcode])) (>= starts 2))})))
        expected-groups [["f01" "f02"] ["f09" "f10"]]
        checks {:index-complete (boolean (:complete? ix))
                :every-file-done (every? (fn [[_ e]] (= :done (:status e))) files)
                :every-runtime-in-meta (every? registry started)
                :every-runtime-in-ran-on (every? ran-on started)
                :mp4s-h264-aac (and (seq mp4) (every? :ok mp4))
                :thumbnails (and (seq dims) (every? :ok dims))
                :contact-sheets (and (seq sheets) (every? :ok sdims))
                :loudness-within-0.5-lu (and (seq louds) (every? :ok louds))
                :two-duplicate-groups (= expected-groups (:duplicates ix))}
        checks (assoc checks
                      ;; every child the boot program wrote was started by its watch, and none was given up on
                      :supervised (and (seq children)
                                       (every? (fn [rid] (pos? (get-in ctl [:members rid :incarnation] 0))) children)
                                       (not-any? (fn [[_ m]] (= :gave-up (:state m))) (:members ctl))))
        checks (cond-> checks
                 (and killed (:coordinator killed)) (assoc :coordinator-restarted (:ok kill-row))
                 (and killed (not (:coordinator killed))) (assoc :kill-attempts-2-restarted (:ok kill-row)))]
    {:ok (every? true? (vals checks))
     :checks checks
     :coordinator (:coordinator ix)
     :supervisor (some-> (get-in ctl [:supervisor :host]) name)
     :members started :registry registry :ran-on ran-on
     :mp4 mp4 :thumbs dims :sheets sdims :loudness louds :duplicates (:duplicates ix)
     :kill kill-row
     :checksums (into (sorted-map) (map (fn [[id _ t]] [id (sha256 t)]) transcodes))
     :thumb-checksums (into (sorted-map) (map (fn [[id t]] [id (sha256 t)]) thumbs))
     :sheet-checksums (into (sorted-map) (map (fn [[id t]] [id (sha256 t)]) sheets))}))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn parse-opts [args]
  (loop [[a & more] args
         opts {}]
    (cond
      (nil? a) opts
      ;; accepted for the old CLI's sake; the boot program always supervises its pools itself
      (= a "--supervisor") (recur (rest more) (assoc opts :supervisor (keyword (first more))))
      (= a "--hosts")
      (recur (rest more)
             (assoc opts :hosts (mapv keyword (str/split (first more) #","))))
      (= a "--kill") (recur (rest more) (assoc opts :kill (first more)))
      (= a "--only") (recur (rest more) (assoc opts :only (first more)))
      (= a "--keep") (recur more (assoc opts :keep true))
      (= a "--no-browser") (recur more (assoc opts :no-browser true))
      (= a "--headless") (recur more (assoc opts :headless true))
      (= a "--install") (recur more (assoc opts :install true))
      (= a "--timeout")
      (recur (rest more) (assoc opts :timeout (parse-long (first more)))) :else
      (recur more (update opts :args (fnil conj []) a)))))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn random-hex [n]
  (apply str (repeatedly n #(rand-nth "0123456789abcdef"))))

(defn display?
  "Whether this machine shows windows: macOS, or a Linux session with an X or Wayland display."
  []
  (boolean (or (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
               (some #(seq (System/getenv %)) ["DISPLAY" "WAYLAND_DISPLAY"]))))

(defn configure!
  "The run's servers configured in the control atom before any member starts: the media server's and the gateway's
   ports, the page's own port (the boot program serves the page itself, from the run's first second), the watched folder
   (the Go member's watcher scans it when a Go member runs), the page's directory, and the gateway's api key and
   store secret, minted for this run."
  [run atoms hosts media-dir]
  (let [cfg {:media-port (free-port)
             :gateway-port (free-port)
             :page-port (free-port)
             :page (str (fs/path (:dir run) "page"))
             :watch (when (some #{:glj} hosts) media-dir)
             :domain-id "cohost-media"
             :api-key (random-hex 32)
             :store-secret (random-hex 32)
             ;; the coordinator: the JVM member (babashka's in a run without a JVM) — named, no election
             :coordinator (if (some #{:jvm} hosts) "jvm" "bb")
             ;; the page in a window of its own where the machine shows windows (--headless: never)
             :show-page (and (not (:headless (:opts run))) (display?))}]
    (swap! (:control atoms) assoc :config cfg)
    cfg))

(defn prepare-page!
  "The page's directory for this run: index.html, the compiled page (target/page/js: main.js, and the manifest.edn its
   worker threads find it by), and its config.json (the domain id and api key it signs in with, the run's output and
   window directories). Is the compiled page there?"
  [run cfg]
  (let [dir (:page cfg)
        built (fs/path app-dir "target" "page" "js")
        js (fs/path built "main.js")]
    (fs/create-dirs (fs/path dir "js"))
    (fs/copy (fs/path app-dir "page" "index.html") (fs/path dir "index.html"))
    (doseq [f ["main.js" "manifest.edn"]
            :when (fs/exists? (fs/path built f))]
      (fs/copy (fs/path built f) (fs/path dir "js" f)))
    (spit (str (fs/path dir "config.json"))
          (str "{\"domain_id\":\"" (:domain-id cfg) "\",\"api_key\":\""
               (:api-key cfg) "\",\"out\":\"" (:out run) "\",\"window\":\""
               (:window run) "\"}"))
    (fs/exists? js)))

;; ---------------------------------------------------------------------------
;; The page server: the boot program serves the run's page itself, from the run's
;; first second — the browser waits for nothing and opens the app at once, and
;; the page watches the members join (starting an app = the app comes up).
;; The page's rung traffic (/gw/, /blobs/) is proxied to the gateway's port;
;; until the gateway is up the proxy answers 503 and the page's rung clients
;; retry. Media outputs come from the run's out directory, with ranges. The
;; Go media server still serves the same page and outputs on :media-port.
;; ---------------------------------------------------------------------------

(def ^:private page-mime
  {"html" "text/html" "js" "text/javascript" "mjs" "text/javascript"
   "json" "application/json" "edn" "application/edn" "css" "text/css"
   "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "svg" "image/svg+xml"
   "ico" "image/x-icon" "mp3" "audio/mpeg" "wav" "audio/wav"
   "mp4" "video/mp4" "webm" "video/webm" "mov" "video/quicktime"})

(defn- http-answer!
  "One HTTP/1.1 response on out; the connection closes after it (the page's
   clients all live with that)."
  [^java.io.OutputStream out status ctype ^bytes body extra head?]
  (let [head (str "HTTP/1.1 " status "\r\n"
                  "Content-Type: " ctype "\r\n"
                  "Content-Length: " (alength body) "\r\n"
                  "Accept-Ranges: bytes\r\n"
                  (str/join "" (map (fn [[k v]] (str k ": " v "\r\n")) extra))
                  "Connection: close\r\n\r\n")]
    (.write out (.getBytes head "UTF-8"))
    (when (and (not head?) (pos? (alength body))) (.write out body))
    (.flush out)))

(defn- read-head
  "Read from in until the blank line ending the headers; the head as a string."
  [^java.io.InputStream in]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (when (neg? b) (throw (ex-info "eof in request head" {})))
        (.write buf b)
        (let [a (.toByteArray buf)
              n (alength a)]
          (if (and (>= n 4)
                   (= 13 (aget a (- n 4))) (= 10 (aget a (- n 3)))
                   (= 13 (aget a (- n 2))) (= 10 (aget a (- n 1))))
            (String. a "UTF-8")
            (if (> n 65536) (throw (ex-info "request head too large" {})) (recur))))))))

(defn- read-exactly
  [^java.io.InputStream in n]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (loop [left n]
      (if (zero? left)
        (.toByteArray buf)
        (let [chunk (byte-array (min 65536 left))
              r (.read in chunk 0 (alength chunk))]
          (when (neg? r) (throw (ex-info "eof in request body" {})))
          (.write buf chunk 0 r)
          (recur (- left r)))))))

(defn- read-request
  "A request off in: {:method :path :headers :body} — headers a lowercase-keyed
   map, body the content-length bytes (or empty)."
  [^java.io.InputStream in]
  (let [head (read-head in)
        lines (str/split-lines head)
        [method path] (str/split (first lines) #" ")
        headers (into {} (keep (fn [l] (let [i (.indexOf ^String l ":")]
                                         (when (pos? i)
                                           [(str/lower-case (subs l 0 i))
                                            (str/trim (subs l (inc i)))])))
                               (rest lines)))
        len (parse-long (get headers "content-length" "0"))]
    {:method method :path path :headers headers
     :body (if (pos? len) (read-exactly in len) (byte-array 0))}))

(def page-isolation-headers
  "Cross-origin isolation for the served page: its in-mem tier is
   SharedArrayBuffer-backed, and SAB is only defined on a
   cross-origin-isolated page — without these headers the page
   dies at boot (\"SharedArrayBuffer is not defined\"). The same
   trio the Go media server sends."
  [["Cross-Origin-Opener-Policy" "same-origin"]
   ["Cross-Origin-Embedder-Policy" "require-corp"]
   ["Cross-Origin-Resource-Policy" "same-origin"]])

(defn- serve-file!
  [^java.io.OutputStream out req file]
  (let [f (fs/file file)
        total (fs/size f)
        range-h (get (:headers req) "range")
        m (and range-h (re-matches #"bytes=(\d*)-(\d*)" range-h))
        [start end] (if m
                      [(if (= "" (nth m 1))
                         (max 0 (- total (parse-long (nth m 2))))
                         (parse-long (nth m 1)))
                       (if (= "" (nth m 2))
                         (dec total)
                         (min (dec total) (parse-long (nth m 2))))]
                      [0 (dec total)])
        len (int (max 0 (inc (- end start))))
        body (byte-array len)
        _ (when (pos? len)
            (with-open [raf (java.io.RandomAccessFile. f "r")]
              (.seek raf (long start))
              (.readFully raf body)))
        ext (str/lower-case (or (fs/extension f) ""))
        ctype (get page-mime ext "application/octet-stream")
        head? (= "HEAD" (:method req))]
    (if m
      (http-answer! out "206 Partial Content" ctype body
                    (into page-isolation-headers
                          [["Content-Range" (str "bytes " start "-" end "/" total)]]) head?)
      (http-answer! out "200 OK" ctype body page-isolation-headers head?))))

(def ^:private http-client (delay (HttpClient/newHttpClient)))

(defn- proxy!
  "The page's rung traffic, forwarded to the gateway's port as-is. The gateway
   not being up yet is a 503 — the page's rung clients retry."
  [^java.io.OutputStream out req gateway-port]
  (try
    (let [hb (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" gateway-port (:path req))))]
      (doseq [k ["content-type" "authorization" "accept" "range"]
              :let [v (get (:headers req) k)]
              :when v]
        (.header hb k v))
      (.method hb (:method req) (HttpRequest$BodyPublishers/ofByteArray ^bytes (:body req)))
      (let [resp (.send ^HttpClient @http-client (.build hb) (HttpResponse$BodyHandlers/ofByteArray))
            body ^bytes (.body resp)
            ;; presigned store URLs name the media server's origin (the gateway's :origin when a Go
            ;; member runs); a page on THIS origin cannot fetch them (cross-origin: no CORS, CORP same-origin).
            ;; Answered here, they are re-based on this origin (the request's Host), so the page — and its
            ;; workers, which cannot resolve a relative URL — read /blobs/ through this proxy.
            host (get (:headers req) "host")
            body (if (and host (str/includes? (:path req) "/presign"))
                   (.getBytes (str/replace (String. body "UTF-8")
                                           #"http://127\.0\.0\.1:\d+/blobs/"
                                           (str "http://" host "/blobs/"))
                              "UTF-8")
                   body)
            headers (.headers resp)
            rh (into [] (keep (fn [k] (let [v (.firstValue headers k)]
                                        (when (.isPresent v) [k (.get v)])))
                              ["content-type" "content-range" "accept-ranges"]))]
        (http-answer! out (str (.statusCode resp) " X")
                      (let [v (.firstValue headers "content-type")]
                        (if (.isPresent v) (.get v) "application/octet-stream"))
                      body rh (= "HEAD" (:method req)))))
    (catch Throwable _
      (http-answer! out "503 Service Unavailable" "text/plain"
                    (.getBytes "the gateway is not up yet\n" "UTF-8") []
                    (= "HEAD" (:method req))))))

(defn- page-connection!
  [page-dir out-dir gateway-port ^java.net.Socket sock]
  (try
    (with-open [in (.getInputStream sock)
                out (.getOutputStream sock)]
      (let [req (read-request in)
            path (java.net.URLDecoder/decode
                  (first (str/split (:path req) #"\?" 2)) "UTF-8")]
        (cond
          (or (str/starts-with? path "/gw/") (str/starts-with? path "/blobs/"))
          (proxy! out req gateway-port)

          (str/includes? path "..")
          (http-answer! out "404 Not Found" "text/plain"
                        (.getBytes "not found\n" "UTF-8") [] false)

          :else
          (let [rel (if (= path "/") "/index.html" path)
                file (some (fn [dir]
                             (let [f (fs/file dir (subs rel 1))]
                               (when (and (fs/exists? f) (not (fs/directory? f))) f)))
                           [page-dir out-dir])]
            (if file
              (serve-file! out req file)
              (http-answer! out "404 Not Found" "text/plain"
                            (.getBytes "not found\n" "UTF-8") [] false))))))
    (catch Throwable _ nil)
    (finally (try (.close sock) (catch Throwable _ nil)))))

(defn serve-page!
  "The boot program's own server for the run's page, on cfg's :page-port. Started
   before the pools start any member, so the browser — which waits for
   nothing — opens the app at the run's first second. Returns the socket; the
   caller closes it at the run's end."
  [run cfg]
  (let [page-dir (:page cfg)
        out-dir (:out run)
        gateway-port (:gateway-port cfg)
        ss (java.net.ServerSocket. (:page-port cfg) 50
                                   (java.net.InetAddress/getByName "127.0.0.1"))]
    (doto (Thread. (fn []
                     (loop []
                       (when-not (.isClosed ss)
                         (try
                           (let [sock (.accept ss)]
                             (doto (Thread. (fn [] (page-connection! page-dir out-dir gateway-port sock)))
                               (.setDaemon true)
                               (.start)))
                           (catch Throwable _ nil))
                         (recur)))))
      (.setDaemon true)
      (.start))
    (log "the page is served by the boot program on port" (:page-port cfg))
    ss))

(defn- http-get-string
  "GET url's body as a string (the CDP driver's /json/list). The request carries a timeout: a peer
   that accepts and never answers (a wedged browser, a mediating sandbox) must fail this call, not
   park the run's main thread past every outer budget — the old launcher's client failed such a
   fetch fast, where java.net.http's send waits forever."
  [url]
  (let [req (.build (-> (HttpRequest/newBuilder (URI/create url))
                        (.timeout (java.time.Duration/ofSeconds 15))))]
    (String. ^bytes (.body (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofByteArray))) "UTF-8")))

(defn- cdp-screenshot!
  "A screenshot of url into shot, taken once the page's table holds n rows (or after timeout-ms): headless Chromium
   driven over the DevTools protocol. (A fixed --virtual-time-budget raced the page: its workers read the library over
   the network rung while virtual time ran on, and the shot caught an empty table.) Answers the rows it saw."
  [url shot n timeout-ms]
  (let [dir (str (fs/create-temp-dir {:prefix "cohost-media-shot-"}))
        proc (p/process {:out :string :err :string} (:chrome tools)
                        "--headless=new" "--no-sandbox" "--disable-gpu"
                        "--no-proxy-server" "--hide-scrollbars"
                        (str "--user-data-dir=" dir) "--window-size=1400,1100"
                        "--remote-debugging-port=0" "about:blank")
        port-file (str (fs/path dir "DevToolsActivePort"))]
    (try
      (let [port (loop [i 0]
                   (cond
                     (and (fs/exists? port-file)
                          (seq (str/trim (slurp port-file))))
                     (first (str/split-lines (slurp port-file))) (< i 200)
                     (do
                       (Thread/sleep 100)
                       (recur (inc i)))
                     :else
                     (throw (ex-info "Chromium opened no DevTools port" {}))))
            targets (json/read-str (http-get-string (str "http://127.0.0.1:" port "/json/list"))
                                   :key-fn keyword)
            ws-url (:webSocketDebuggerUrl (first (filter (fn [t]
                                                           (= "page" (:type t)))
                                                         targets)))
            pending (atom {})
            ids (atom 0)
            ;; (a large reply — the screenshot — arrives in parts: a message is whole at its last part)
            part (StringBuilder.)
            listener (reify WebSocket$Listener
                       (onOpen [_ ws] (.request ws 1) nil)
                       (onText [_ ws data last?]
                         (.append part (str data))
                         (when last?
                           (let [m (json/read-str (str part) :key-fn keyword)]
                             (.setLength part 0)
                             (when-let [pr (get @pending (:id m))]
                               (deliver pr m))))
                         (.request ws 1)
                         nil)
                       (onError [_ _ws _err] nil))
            ^WebSocket sock (.join (.buildAsync (.newWebSocketBuilder ^HttpClient @http-client)
                                                (URI/create ws-url) listener))
            call (fn [method params]
                   (let [id (clojure.core/swap! ids inc)
                         pr (promise)]
                     (clojure.core/swap! pending assoc id pr)
                     (.sendText sock (json/write-str {:id id
                                                      :method method
                                                      :params params}) true)
                     (let [r (deref pr 30000 nil)]
                       (clojure.core/swap! pending dissoc id)
                       (:result r))))
            rows (fn []
                   (or (get-in (call "Runtime.evaluate"
                                     {:expression "document.querySelectorAll('tbody tr').length"
                                      :returnByValue true}) [:result :value]) 0))
            _ (call "Page.navigate" {:url url})
            t0 (now)
            seen (loop []
                   (let [r (rows)]
                     (if (or (>= r n) (> (- (now) t0) timeout-ms))
                       r
                       (do
                         (Thread/sleep 500)
                         (recur)))))]
        ;; (the thumbnails load after their rows)
        (Thread/sleep 1500)
        (when-let [png (:data (call "Page.captureScreenshot" {:format "png"}))]
          (fs/write-bytes shot
                          (.decode (java.util.Base64/getDecoder) ^String png)))
        (.sendClose sock WebSocket/NORMAL_CLOSURE "done")
        seen)
      (finally (p/destroy-tree proc)))))

(defn browser-check!
  "Wait (up to timeout-ms) for the page's check (the browser member, started with ?check=f01:thumb, writes it into
   control :page-check); then a screenshot of the page (?runtime=0: a view only, no second browser runtime), once its
   table shows every file of the library."
  [run atoms cfg timeout-ms]
  (let [t0 (now)
        got (loop []
              (or (:page-check @(:control atoms))
                  (when (< (- (now) t0) timeout-ms) (Thread/sleep 500) (recur))))
        shot (str (fs/path (:dir run) "page.png"))
        n (count (:files @(:library atoms)))
        [ok? seen] (try
                     [true
                      (cdp-screenshot! (str "http://127.0.0.1:"
                                            (:page-port cfg)
                                            "/index.html?runtime=0") shot n
                                       60000)]
                     (catch Exception e [false (ex-message e)]))]
    (log "browser check" (if got "received" "NOT received") "screenshot" shot
         (if ok? (str seen " of " n " rows") (str "failed: " seen)))
    (assoc (or got {:ok false}) :screenshot (when (fs/exists? shot) shot)
           :screenshot-rows (when ok? seen))))

(defn- newest
  "The newest modification time (ms) of the Clojure sources under dirs."
  [dirs]
  (reduce max 0
          (for [d dirs
                :when (fs/exists? d)
                f (fs/glob d "**.{clj,cljs,cljc,glj,clje,go}")]
            (.toMillis (fs/last-modified-time f)))))

(defn- stale? [target dirs]
  (or (not (fs/exists? target))
      (< (.toMillis (fs/last-modified-time target)) (newest dirs))))

;; ---------------------------------------------------------------------------
;; What a run needs of this machine, checked before anything starts
;; ---------------------------------------------------------------------------

(def jolt-runs?
  "Does the jolt at jolt run here (jolt -e, with the Chez Scheme chez where it is a source tree's)? Once per pair."
  (memoize
   (fn [jolt chez]
     (boolean
      (when (and jolt (fs/executable? jolt))
        (let [{:keys [exit out]} (p/shell {:continue true :out :string :err :string
                                           :extra-env (cond-> {"PATH" (:path tools)} chez (assoc "JOLT_CHEZ" chez))}
                                          jolt "-e" "(+ 1 2)")]
          (and (zero? exit) (= "3" (str/trim (str out))))))))))

(defn ffmpeg-libs
  "The majors of FFmpeg's libavutil this machine's loader finds — 58, 59, 60: FFmpeg 6.1, 7.x, 8.x, what jolt's
   transcoder reads (src/cohost_media/av_jolt.clj) — in EVE_FFMPEG_LIB, Homebrew's and MacPorts' lib directories
   on macOS, the loader's cache (ldconfig -p) on Linux."
  []
  (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        dirs (concat (keep not-empty [(System/getenv "EVE_FFMPEG_LIB")])
                     (when mac? ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib"]))
        cache (when-not mac?
                (str (:out (p/shell {:continue true :out :string :err :string} "sh" "-c"
                                    "ldconfig -p 2>/dev/null || /sbin/ldconfig -p"))))]
    (set (filter (fn [m]
                   (let [file (if mac? (str "libavutil." m ".dylib") (str "libavutil.so." m))]
                     (or (some (fn [d] (fs/exists? (fs/path d file))) dirs)
                         (and cache (str/includes? cache file)))))
                 [58 59 60]))))

(defn checks
  "What hosts need of this machine, one check each: {:ok? :msg :brew [formulae] :cask name}. Linux and macOS
   run every member."
  [hosts]
  (let [hosts (set hosts)
        os (str/lower-case (System/getProperty "os.name"))
        linux? (str/includes? os "linux")
        on-path? (fn [c] (some? (fs/which c)))
        ffmpeg? (on-path? "ffmpeg")
        ck (fn [ok? msg & {:as how}] (merge {:ok? (boolean ok?) :msg msg} how))]
    (vec (remove nil?
                 [(ck ffmpeg? "ffmpeg on PATH (the demo's media, every transcode, the loudness check)" :brew ["ffmpeg"])
                  (ck (on-path? "ffprobe") "ffprobe on PATH (the checks read every output's streams)" :brew ["ffmpeg"])
                  (ck (or (not ffmpeg?) (contains? @encoders "libx264"))
                      "an ffmpeg built with libx264 (every transcode is H.264): Homebrew's, a distribution's package")
                  (when (:jvm hosts) (ck (on-path? "java") "java (the JVM member)" :brew ["openjdk"]))
                  (when (:jvm hosts) (ck (on-path? "clojure") "the clojure CLI (the boot program's and the builds' launcher)" :brew ["clojure"]))
                  (when (:node hosts) (ck (and (on-path? "node") (on-path? "npm"))
                                          "node and npm (the Node member; eve's native addon builds on npm install)" :brew ["node"]))
                  (when (:glj hosts) (ck (on-path? "go") "go (the Go member's glj-cohost-media is built from source)" :brew ["go"]))
                  (when (:browser hosts)
                    (ck (fs/executable? (:chrome tools))
                        "a Chromium or Chrome for the page (none found: EVE_CHROME=/path/to/chrome, or install one)"
                        :cask "google-chrome"))
                  (when (:jolt hosts)
                    (ck (jolt-runs? (:jolt tools) (:jolt-chez tools))
                        (str "jolt, running (jolt -e): Homebrew's (macOS arm64 14+), jolt's install script "
                             "(curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash), or a source "
                             "tree with its Chez Scheme (JOLT_CHEZ)")
                        :brew ["jolt-lang/jolt/jolt"]))
                  (when (:jolt hosts)
                    (ck (seq (ffmpeg-libs))
                        (str "FFmpeg's shared libraries, 6.1, 7.x or 8.x (libavutil 58-60), for jolt's transcoder: "
                             "Homebrew's ffmpeg, a distribution's libavcodec/libavformat packages, or EVE_FFMPEG_LIB=<dir>")
                        :brew ["ffmpeg"]))
                  (when (and (:jolt hosts) linux?)
                    (ck (str/includes? (str (:out (p/shell {:continue true :out :string :err :string} "sh" "-c"
                                                           "ldconfig -p 2>/dev/null || /sbin/ldconfig -p")))
                                       "libatomic.so.1")
                        "libatomic.so.1 (jolt's eve regions call its atomics): a distribution's libatomic1 package"))]))))

(defn- how
  "What bb cohost-media setup does for a failing check (Homebrew's where there is one, or on macOS), or nil."
  [{:keys [brew cask]}]
  (let [brew? (or (some? (fs/which "brew")) (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))]
    (cond (and brew? brew) (str "brew install " (str/join " " brew))
          (and brew? cask) (str "brew install --cask " cask))))

(defn preflight
  "What hosts need of this machine that it lacks: one line each, with what installs it (empty: ready)."
  [hosts]
  (vec (keep (fn [c] (when-not (:ok? c) (str (:msg c) (some->> (how c) (str " — "))))) (checks hosts))))

(defn setup!
  "Install what hosts need and this machine lacks, where it can: Homebrew's formulae and casks. Then the
   checks again; answers what is still missing."
  [hosts]
  (let [hosts (set hosts)
        failing (remove :ok? (checks hosts))
        brew? (some? (fs/which "brew"))
        formulae (distinct (mapcat :brew failing))
        casks (distinct (keep :cask failing))]
    (when (and (seq (concat formulae casks)) (not brew?))
      (log "setup: no Homebrew (https://brew.sh) — install these yourself:" (str/join ", " (concat formulae casks))))
    (when (and brew? (seq formulae))
      (log "setup: brew install" (str/join " " formulae))
      (apply p/shell "brew" "install" formulae))
    (when (and brew? (seq casks))
      (log "setup: brew install --cask" (str/join " " casks))
      (apply p/shell "brew" "install" "--cask" casks))
    (alter-var-root #'tools (fn [_] (find-tools)))
    (let [left (preflight hosts)]
      (if (seq left)
        (log "setup: still missing:\n  " (str/join "\n  " left))
        (log "setup: ready for" (str/join " " (map name (sort hosts)))))
      left)))

(defn ensure-built!
  "What the members and the page run, built when absent or older than their sources (force?: always): the npm
   libraries, the Node member and the page (shadow-cljs through deps.edn's :cohost-media alias), glj-cohost-media (Go)."
  [hosts force?]
  (let [hosts (set hosts)
        sh! (fn [what dir & cmd]
              (log "build:" what)
              (let [r (apply p/shell
                             {:dir dir :continue true :out :string :err :string}
                             cmd)]
                (when-not (zero? (:exit r))
                  (println (:out r) (:err r))
                  (throw (ex-info (str "build failed: " what) {:cmd cmd})))))
        eve-src (str (fs/path eve-dir "src"))
        ;; (the adapters a ClojureScript build composes: the browser's http and thread adapters, Node's http server)
        eve-adapters (str (fs/path eve-dir "adapters"))
        app-src (str (fs/path app-dir "src"))]
    ;; eve's own npm packages (shadow-cljs) and its native addon (built by npm install: node-gyp-build), which the Node
    ;; member's disk domains load
    (when (and (:node hosts)
               (or force?
                   (not (fs/exists? (fs/path eve-dir "build" "Release" "mmap_cas.node")))
                   (not (fs/exists? (fs/path eve-dir "node_modules" "shadow-cljs")))))
      (sh! "npm install in eve/ (shadow-cljs; the native addon)" eve-dir "npm" "install"
           "--no-audit" "--no-fund"))
    (when (and (:node hosts)
               (or force?
                   (not (fs/exists? (fs/path app-dir "node_modules" "sharp")))))
      (sh! "npm install (sharp, music-metadata)" app-dir "npm" "install"
           "--no-audit" "--no-fund"))
    (when (and (:node hosts)
               (or force?
                   (stale? (fs/path app-dir "target" "node-member.js")
                           [eve-src
                            eve-adapters
                            app-src
                            (str (fs/path app-dir "node" "src"))])))
      (sh! "the Node member (shadow-cljs release cohost-media-node)" eve-dir
           "clojure" "-M:node-test:cohost-media-node" "-m"
           "shadow.cljs.devtools.cli" "release" "cohost-media-node"))
    (when (and (:glj hosts)
               (or force?
                   (stale? (fs/path app-dir "target" "page" "js" "main.js")
                           [eve-src
                            eve-adapters
                            app-src
                            (str (fs/path app-dir "page" "src"))])))
      (sh! "the page (shadow-cljs release cohost-media-page)" eve-dir "clojure"
           "-M:browser-test:cohost-media-page" "-m" "shadow.cljs.devtools.cli"
           "release" "cohost-media-page"))
    (when (:glj hosts)
      (sh! "glj-cohost-media (glj/build.sh; cached by its source stamp)" app-dir
           "bash" "glj/build.sh"))
    nil))

(defn drop-file!
  "Live pick-up: once the index is complete, a new file dropped into the watched folder (an eleventh, generated here:
   three seconds of tone, written under a name the watcher skips and renamed into place); then the watcher's entry for
   it and the index are waited for again. {:ok :file :id :found-by :status :picked-up-ms :ms}."
  [run atoms procs state media-dir timeout-ms]
  (let [dest (str (fs/path media-dir "f11-dropped.wav"))
        part (str dest ".part")]
    (ffmpeg! "-f" "lavfi" "-i" "sine=frequency=330:duration=3:sample_rate=48000"
             "-ac" "2" "-f" "wav" part)
    (let [t0 (now)]
      (fs/move part dest)
      (log "dropped" dest "into the watched folder")
      (let [seen (loop []
                   (cond
                     (get-in @(:library atoms) [:files dest]) (- (now) t0)
                     (> (- (now) t0) 30000) nil
                     :else
                     (do
                       (Thread/sleep 200)
                       (recur))))
            ok? (and seen (wait-complete! run atoms procs state timeout-ms))
            e (get-in @(:library atoms) [:files dest])]
        (log "the dropped file"
             (if seen (str "picked up in " seen " ms") "NOT picked up")
             "; index" (if ok? "complete" "not complete"))
        {:ok (boolean (and ok? e (= :done (:status e))
                           (= 11 (count (:files @(:library atoms))))))
         :file dest
         :id (:id e)
         :found-by (:found-by e)
         :status (:status e)
         :picked-up-ms seen
         :ms (- (now) t0)}))))

(defn run-library!
  "A run on a fresh domain: its children written into the control atom (the gateway, a member per runtime, the
   browser) and the pools started — the gateway and the JVM member in this process, the rest as process-backed
   pools on their native seats, all watched by the boot program; dir's media written into the library
   (by the Go member's watcher, or the boot program's scan when no Go member runs); the index waited for; the page's check;
   the report. With :drop, the media are copied into the run and an eleventh file is dropped into the watched folder
   once the index is complete."
  [opts media-dir]
  (let [hosts (let [hs (or (:hosts opts) default-hosts)
                    jolt? (or (not (some #{:jolt} hs)) (:strict opts) (empty? (preflight [:jolt])))]
                (when-not jolt?
                  (log "jolt cannot run here (" (str/join "; " (preflight [:jolt])) "): the JVM's ffmpeg CLI transcodes"))
                (vec (cond->> hs
                       (not jolt?) (remove #{:jolt})
                       (or (:no-browser opts) (not (some #{:glj} hs)))
                       (remove #{:browser}))))
        browser? (boolean (some #{:browser} hosts))
        _ (when (:install opts) (setup! hosts))
        _ (when-let [missing (seq (preflight hosts))]
            (throw (ex-info (str "cohost-media: this machine lacks what " (str/join " " (map name hosts)) " need:\n  "
                                 (str/join "\n  " missing) "\n--install installs what it can (Homebrew)")
                            {:missing missing})))
        _ (ensure-built! hosts false)
        run (new-run opts)
        _ (log "run" (:dir run) "window" (:window run))
        media-dir (if (:drop opts)
                    (let [d (str (fs/path (:dir run) "media"))]
                      (fs/copy-tree media-dir d)
                      d)
                    media-dir)
        atoms (domain/open-atoms (:base run))
        cfg (configure! run atoms hosts media-dir)
        page? (prepare-page! run cfg)
        page-srv (when page? (serve-page! run cfg))
        procs (atom {})
        state (atom {})]
    (try
      ;; the children, written on the disk before any of them runs; then the pools, which start them
      (swap! (:control atoms) assoc :children
             (child-specs run cfg (cond->> hosts (not page?) (remove #{:browser}))))
      (start-pools! run atoms cfg hosts procs)
      ;; the gateway runs in this process, started with the pools: wait for its note before any gateway I/O
      (wait-gateway! atoms (or (:timeout opts) 600000))
      (if (:watch cfg)
        (log "the Go member's watcher scans" media-dir)
        (log "scan" media-dir (scan! atoms media-dir) "files"))
      (let [t0 (now)
            timeout (or (:timeout opts) 600000)
            ok? (wait-complete! run atoms procs state timeout)
            wall (- (now) t0)
            dropped (when (and ok? (:drop opts) (:watch cfg))
                      (drop-file! run atoms procs state media-dir timeout))
            page-check (when (and browser? page?)
                         (browser-check! run atoms cfg 180000))
            nfiles (count (:files @(:library atoms)))
            base (if ok?
                   (check run atoms state)
                   {:ok false :checks {:index-complete false}})
            base (cond-> base
                   browser?
                   (assoc-in [:checks :browser-library-and-requeue]
                             (boolean (and (:ok page-check)
                                           (= nfiles (:rows page-check))
                                           (= :done (last (:seen page-check))))))
                   (:drop opts)
                   (assoc-in [:checks :watcher-new-file]
                             (boolean (:ok dropped))))
            base (assoc base :ok (every? true? (vals (:checks base))))
            report (assoc base :wall-ms wall :hosts hosts :run (:dir run) :set
                          (:set opts) :restarts (:restarts @(:control atoms))
                          :page page-check :drop dropped :servers
                          (:servers @(:control atoms)) :found-by
                          (frequencies (map (comp :found-by val)
                                            (:files @(:library atoms)))))]
        (log (if ok? "complete in" "TIMED OUT after") wall "ms")
        (spit (str (fs/path (:dir run) "report.edn")) (pr-str report))
        (log "report" (str (fs/path (:dir run) "report.edn")))
        (println (pr-str (select-keys report
                                      [:ok
                                       :checks
                                       :coordinator
                                       :supervisor
                                       :members
                                       :ran-on
                                       :kill
                                       :drop
                                       :wall-ms])))
        (when (and (:stay opts) browser? page? (:show-page cfg))
          (log "the page stays up:" (str "http://127.0.0.1:" (:page-port cfg) "/index.html") "— Enter ends the run")
          (read-line))
        report)
      (finally
        (stop-members! atoms procs)
        (when page-srv (try (.close ^java.net.ServerSocket page-srv) (catch Throwable _ nil)))))))

(defn demo-media!
  "The demo's ten files (generated once into ~/.cohost-media/demo-media): their directory."
  []
  (let [media (str (fs/path (fs/home) ".cohost-media" "demo-media"))]
    (when-not (and (fs/exists? media) (= 10 (count (fs/list-dir media))))
      (fs/delete-tree media)
      (log "generating ten demo files (lavfi) in" media)
      (generate-demo! media))
    media))

(def verify-runs
  "The done bar's runs: the whole set undisturbed (the page, and an eleventh file dropped into the watched folder);
   jolt killed mid-transcode, and the coordinator killed mid-run — each started again by its pool's watch; the
   ffmpeg CLI transcoding (no jolt). The coordinator-kill run has no JVM member: its coordinator is babashka, a
   process member the watch restarts — with a JVM member the coordinator runs in the boot process itself and
   there is no pid to kill."
  [{:name "full" :hosts default-hosts :browser true :drop true}
   {:name "full-kill" :hosts (vec (remove #{:browser} default-hosts)) :kill "transcode"}
   {:name "coordinator-kill" :hosts [:bb :node :glj :jolt] :kill "coordinator"}
   {:name "cli-transcode" :hosts (vec (remove #{:browser :jolt} default-hosts))}])

(defn verify!
  "Every run of verify-runs, then the comparisons the done bar asks for: each run's checks; the killed transcode at
   :attempts 2 with the undisturbed run's checksum; the killed coordinator started again and the same outputs.
   Writes ~/.cohost-media/verify-<stamp>.edn; answers it."
  [opts]
  (let [only (some-> (:only opts) (str/split #",") set)
        runs (filterv (fn [r] (or (nil? only) (contains? only (:name r))))
                      verify-runs)
        _ (when (:install opts) (setup! (distinct (mapcat :hosts runs))))
        unready (keep (fn [r] (when-let [m (seq (preflight (:hosts r)))] [(:name r) m])) runs)
        _ (when (seq unready)
            (let [ready (remove (set (map first unready)) (map :name runs))]
              (throw (ex-info (str "cohost-media verify: run(s) this machine cannot start:\n"
                                   (str/join "\n" (map (fn [[n m]] (str "  " n ": " (str/join "; " m))) unready))
                                   "\nbb cohost-media verify --install installs what it can"
                                   (when (seq ready) (str "\nthe ones it can run now: bb cohost-media verify --only " (str/join "," ready))))
                              {:unready (into {} unready)}))))
        media (demo-media!)
        reports (reduce (fn [acc {:keys [name hosts kill browser drop]}]
                          (log "=== verify run" name hosts (if kill (str "kill " kill) ""))
                          (assoc acc name
                                 (run-library! (cond-> (assoc opts :hosts hosts :set name :strict true)
                                                 kill (assoc :kill kill)
                                                 drop (assoc :drop true)
                                                 (not browser) (assoc :no-browser true))
                                               media)))
                        {} runs)
        ten (map (fn [i] (str "f" (when (< i 10) "0") i)) (range 1 11))
        outputs (fn [r]
                  (-> (select-keys r [:checksums :thumb-checksums :sheet-checksums :duplicates])
                      (update :checksums select-keys ten)
                      (update :thumb-checksums select-keys ten)
                      (update :sheet-checksums select-keys ten)))
        same? (fn [a b]
                (and (get reports a) (get reports b)
                     (= (outputs (get reports a)) (outputs (get reports b)))))
        kill-ok? (fn [k base]
                   (let [row (:kill (get reports k))]
                     (and row (:ok row) (get reports base)
                          (= (:sha256 row) (get-in (get reports base) [:checksums (:file row)])))))
        rows (cond-> {}
               (get reports "full")
               (assoc :full-checks (:ok (get reports "full"))
                      :full-supervised-by-boot (= "boot" (:supervisor (get reports "full"))))
               (get reports "full-kill")
               (assoc :full-kill-attempts-2-same-checksum (boolean (kill-ok? "full-kill" "full")))
               (get reports "coordinator-kill")
               (assoc :coordinator-kill-restarted (boolean (get-in reports ["coordinator-kill" :kill :ok]))
                      :coordinator-kill-same-outputs (boolean (same? "full" "coordinator-kill")))
               (get reports "cli-transcode")
               (assoc :cli-transcode-checks (:ok (get reports "cli-transcode"))))
        all-checks (into {} (map (fn [[n r]] [n (:ok r)]) reports))
        out {:ok (and (every? true? (vals rows)) (every? true? (vals all-checks)))
             :rows rows
             :run-checks all-checks
             :runs (into {} (map (fn [[n r]]
                                   [n (select-keys r [:run :hosts :coordinator :supervisor :wall-ms :kill :checks
                                                      :ran-on :checksums :page :drop])])
                                 reports))}
        f (str (fs/path (fs/home) ".cohost-media"
                        (str "verify-" (str/replace (str (java.time.LocalDateTime/now)) #"[:.]" "-") ".edn")))]
    (spit f (pr-str out))
    (log "verify" (if (:ok out) "PASSED" "FAILED") f)
    (println (pr-str (select-keys out [:ok :rows :run-checks])))
    out))

(defn -main [& [cmd & args]]
  (let [opts (parse-opts args)]
    (try
    (case cmd
      "demo"
      (let [r (run-library! (assoc opts :stay true) (demo-media!))]
        (System/exit (if (:ok r) 0 1)))
      "scan"
      (let [r (run-library! (assoc opts :stay true) (str (fs/absolutize (first (:args opts)))))]
        (System/exit (if (:ok r) 0 1)))
      "verify"
      (let [r (verify! opts)]
        (System/exit (if (:ok r) 0 1)))
      "setup"
      ;; what the runs named (--only, verify's) or the hosts need: installed where it can be
      (let [hosts (if-let [only (some-> (:only opts) (str/split #",") set)]
                    (distinct (mapcat :hosts (filter #(only (:name %)) verify-runs)))
                    (or (:hosts opts) default-hosts))]
        (System/exit (if (empty? (setup! hosts)) 0 1)))
      "build"
      (do
        (ensure-built! default-hosts true)
        (System/exit 0))
      (do
        (println "usage: bb cohost-media demo|scan <dir>|verify|setup|build [--hosts h,...]"
                 "[--kill transcode|coordinator] [--no-browser] [--headless] [--only run,...]"
                 "[--install]")
        (System/exit 2)))
    ;; what this machine lacks: the message alone, no stack trace
    (catch clojure.lang.ExceptionInfo e
      (if (let [d (ex-data e)] (and (map? d) (or (:unready d) (:missing d))))
        (do (binding [*out* *err*] (println (ex-message e))) (System/exit 1))
        (throw e))))))
