# cohost-media-example

The bare-minimum cohost example: one media pipeline, written once as ordinary
Clojure, running across every cohost runtime at once — JVM Clojure, babashka,
jolt, glojure, Node (ClojureScript) and the browser — with all of them
coordinating through a single Eve disk atom.

A boot program scans a folder of media files and plans the work as jobs in the
atom: transcode, loudness, thumbnails, contact sheets, metadata, duplicate
detection. Each runtime joins as a pool with declared capabilities and claims
the jobs it can run; a web page (itself a pool member, over the network) shows
the library filling in live. Nothing in the app knows which runtime will pick
up a job — the pools are data in the boot program's config, the task code is
one shared namespace every runtime loads whole, and there are no
per-runtime folders.

## Run it

You need: babashka (≥ 1.13.220), the Clojure CLI, a JDK (21), Node + npm,
ffmpeg, and Go (for the glojure member). jolt is optional — without it the
JVM transcodes.

```
bb demo
```

The task clones the two dependencies as siblings at pinned commits — Eve
(`SeniorCareMarket/eve`, tag `v0.9.3.2`) and
`johnmn3/cohost.cljc` — syncs this app into the Eve tree at
`eve/capstone/cohost-media`, and runs Eve's own `bb cohost-media demo` there.
First run builds what it needs (the browser page, the Node member, the
glojure binary) and takes a few minutes; later runs are quick. A browser
window opens on the library page; the run ends when every file is done.

To choose the pools: `bb demo --hosts bb,jvm,glj,node,browser`.

## Layout

Every app file is `.cljc` — the same sources read on every runtime; the few
JS-specific deps host out to the browser runtime through reader conditionals.

- `src/cohost_media/boot.cljc` — the host entry (the only main): installs
  this seat's cohost adapter and calls the program.
- `src/cohost_media/start.cljc` — the boot program itself: preflight, builds,
  pool configs as data, the run's supervisor. All runtime starting is thread
  starting here, on cohost — the browser member included: it starts as a
  cohost browser thread, which also serves the library page and the
  gateway's endpoints.
- `src/cohost_media/member.cljc` — the member every runtime runs: attach to
  the job atom, claim by capability, renew the lease, complete.
- `src/cohost_media/tasks.cljc` — the job vocabulary, shared by all runtimes;
  the work itself lives in the facility namespaces beside it (`work.cljc`,
  `media.cljc`, `av.cljc`, `meta.cljc`, …) — plain `clojure.*` calls; Eve
  arrives through the cohost surface, not by calling into Eve.
- `page/` — the library page (one `.cljc` file); its workers are a pool too.
- `node/` — the Node member's entry point (a `.cljc` file, compiled
  ClojureScript on that seat).
- `glj/build.sh` — builds the glojure member's binary; it is Eve's own
  glojure build, nothing added.
- `deps.edn` — the app's Maven deps only. Eve and cohost are pinned in
  `bb.edn`'s setup task.
