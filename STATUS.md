# Syntax formalization lane — cohost-media-example

Branch: `syntax/formalization-1` from origin/main `e096566b7299`
(ls-remote verified). Plan: ~/workspace/syntax-formalization/PLAN.md.

## Execution-risk flag — noted once, per brief

The fresh clone was flagged by the runtime's execution-risk check and
two prior lane attempts stopped on it. John Newman was told in plain
words and authorized working in this clone twice in the main chat on
Sun Nov 22 2026 (option tap + typed confirmation, 13:52 ET). This lane
proceeds under that authorization; the same flag re-triggering on this
clone is already answered.

## Tool installs (step 0)

- clj-kondo **2026.10.26** — GitHub release binary → ~/.local/bin. ✓
  (`clj-kondo --version` → v2026.10.26)
- bbin 0.2.3 → ~/.local/bin (raw script from babashka/bbin v0.2.3). ✓
  NOTE: bb processes resolve user.home to /root, so bbin shims land in
  /root/.local/bin and libs in /root/.gitlibs (ephemeral across VM
  replacements; reinstall route is this section). Working shims are
  mirrored to /home/hatch/.local/bin.
- cljfmt **0.16.6** — Maven coordinate 404s on every reachable repo
  (repo1 through the proxy, repo.maven.apache.org, Clojars), so
  installed via bbin git coords at tag 0.16.6 (sha baab5008) with
  --main-opts '["-m" "cljfmt.main"]' (bbin's derived main
  `weavejester.cljfmt` does not exist in 0.16.6). ✓ `cljfmt --version`
  → 0.16.6.
- carve **v0.3.5** (bbin git, sha 6f0f3bf) ✓. NOTE: carve analyses with
  its own bundled clj-kondo pod (2023.01.20), not the fleet kondo —
  retro material.
- clojure-mcp-light **0.2.2** (bbin git, tag v0.2.2, sha d341c239):
  `clj-paren-repair` + `clj-paren-repair-claude-hook` bins installed. ✓
  - DEFECT (proven, fresh repro): under bb 1.3.190 both entry points
    die at SCI analysis of hook.clj — `timbre/set-config!` was removed
    in the timbre v6 that bb 1.3.190 bundles, and -cp/BABASHKA_CLASSPATH
    cannot shadow a bb built-in. Under **bb v1.13.229** (`bb-new` on
    this box) the bundled timbre has set-config! and the tool works:
    verified on a scratch file ("Reformatted repair-test.clj", body
    correctly re-indented). This lane therefore runs bb-new as `bb`
    (shim: /home/hatch/.local/bbnew-bin/bb → bb-new) — which the demo
    requires anyway (README: bb ≥ 1.13.220; stock bb here is 1.3.190).
  - bb-new's vendored tools.deps ignores CLJ_CONFIG's :mvn/local-repo
    and uses /root/.m2/repository; org.clojure/clojure 1.12.5,
    spec.alpha 0.5.238, core.specs.alpha 0.4.74 (pom+jar) seeded
    there and in /home/hatch/m2repo from repo.maven.apache.org.
- Splint 1.25.0 as `bb splint` — wired in bb.edn (namespace is
  `noahtheduke.splint`, not splint.core). Deps seeded into both local
  repos from Clojars/repo.maven.apache.org (splint + data.json,
  spec.alpha 0.6.249, tools.cli, rewrite-clj 1.2.55, flatland ordered,
  fipp, edamame, camel-snake-kebab, tools.reader, rrb-vector,
  tools.macro, pom.contrib 1.4.0). ✓ verified: lints, exits 1 with
  findings.
- Kondo cache generated for the deps classpath
  (`clj-kondo --lint "$(clojure -Spath)"`, 1.3M, left untracked —
  CI caches it per the plan).

## Layout adaptation (recorded per brief)

No `test/` dir. Lint/format paths are `src page/src` everywhere the
fleet template says `src test`.

## Progress

- [x] Step 0 tools complete
- [x] Step 1 templates in + kondo cache + commit
- [x] Step 2 census (CENSUS.md @ f5a077e)
- [~] Step 3 format pass committed (b52ceaf); boot check — see below
- [ ] Step 4 fix campaign (via the integrated edit pipeline:
      clj-replace → clj-paren-repair → clj-kondo)
- [ ] Step 6 gates + integrated pipeline trial
- [ ] Step 7 dprint
- [ ] Step 8 RETRO.md

## Named exceptions (suppressions / disables)

(none yet)

## Step 3 boot check — the record so far (2026-11-22 evening)

Environment for all runs: bb 1.13.229 as bb, go on PATH
(/home/hatch/.local/go/bin), EVE_CHROME = Playwright Chromium 147.
A VM replacement mid-evening wiped /tmp and /root; tools were
restored from the durable copies (this section's install notes) and
the branch was verified intact at b52ceaf on origin.

- Run 1 (all hosts): preflight failed — go/Chrome not on PATH yet.
  Not an app failure.
- Runs 2–3 (all hosts): the page build failed inside the demo.
  Run 3's subprocess output shows the actual cause: shadow-cljs
  par-compile abort — `eve/net/wire.cljc still waiting for
  #{eve.net.json}` — in Eve's own sources, under load.
- Direct discriminator: the identical shadow build command, run
  standalone on the formatted tree, **completed** (147 files,
  84 compiled, 382.67s). The formatted page/app sources compile.
- Run 4 (all hosts): page build passed; Eve's glojure build failed —
  its build.sh wraps `go build` in a 300 s timeout (`to 300`); a cold
  Go cache on this loaded box (1.5 shared CPUs, many seats) exceeds
  it. Standalone manual build: 2m5s; scripted rebuild with warm
  cache: 18.6s, stamp written by build.sh itself.
- Run 5/6 (all hosts, then reduced `--hosts bb,jvm,glj,jolt`):
  **the app boots and runs** — domain created, pools up, bb member
  joined (caps #{:index}), jolt joined (#{:pcm-native
  :transcode-native}), glj media-server + watcher indexed all ten
  demo files, browser member started. But the coordinator never
  planned jobs: progress stayed {} until the program's own patience
  thresholds fired (90 s no-progress abort in run 5; report
  `{:ok false, :checks {:index-complete false}}`, wall 148.9 s, in
  the reduced run). Reproducible in both runs.
- CONTROL IN FLIGHT: the identical reduced demo on pristine upstream
  main e096566 (zero lane changes) in scratch/control-pre-format.
  If it stalls identically, the stall is a pre-existing condition at
  this pin/environment and the format pass is exonerated; if it
  completes, my branch is the cause and step 3 reverts.

Boot-check verdict: PENDING the control. What is already proven:
the formatted sources parse and compile everywhere they are compiled
(cljs page build over the whole app graph; JVM loading of the full
boot program; three member runtimes executing the shared .cljc
sources), and every build failure so far traces to this box's load
against Eve-side build machinery, never to an app file.
