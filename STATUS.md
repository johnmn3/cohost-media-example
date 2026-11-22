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
- [~] Step 4 fix campaign: unused-import ✓ (e3a1ab8),
  redundant-nested-call ✓ (c458be4), redefined-var ✓ (c03f558)
- [x] Step 6 integrated-pipeline trial (3 real edits + exit-4) — below

## Integrated pipeline trial (step 6, per the Nov 22 steer)

Pipeline: clj-replace → clj-paren-repair → clj-kondo, per edit.

| Edit | replace exit | repair == cljfmt fix | 2nd repair no-op | kondo before → after |
|---|---|---|---|---|
| start.cljc unused-import (drop BodyPublishers) | 0 | YES byte-for-byte | YES | 6e/29w → 6e/28w, finding gone |
| phash.cljc redundant-nested-call | 0 | YES byte-for-byte | YES | info finding gone; file 0e/0w |
| start.cljc redefined-var (display? split) | 0 (×2 replaces) | YES byte-for-byte | YES | 6e/28w → 6e/27w, finding gone |

Exit-4 exercise (scratch file): old `(def n 255)` vs file `(def n
0xFF)` → exit 4, file's exact text printed, file md5 UNCHANGED;
retry with the printed text → exit 0, repair clean. Same for a
comment-in-form near-match. One disagreement with the skill doc: its
pinned deref case (`@a` vs `(deref a)`) exits 2 ("no match") under
bb 1.13.229 here, not 4 as the skill's own verification recorded —
near-match detection is environment-sensitive; the fail-closed
property (no edit on non-zero exit) held in every case.
- [ ] Step 4 fix campaign (via the integrated edit pipeline:
      clj-replace → clj-paren-repair → clj-kondo)
- [ ] Step 6 gates + integrated pipeline trial
- [ ] Step 7 dprint
- [ ] Step 8 RETRO.md

## Named exceptions (suppressions / disables)

1. **`.cljfmt.edn` deviates from the fleet template in exactly one
   key: `:sort-ns-references? false`** (template: true). Reason:
   sorting :require lists changes namespace load order — semantics,
   not formatting. Proven below (step 3 resolution). Proposed as a
   fleet template change in RETRO.md.
2. **Four `#_{:clj-kondo/ignore [:constant-condition]}` site
   suppressions** (av.cljc transcode!/pcm!, media.cljc
   init!/publish!). Reason: verified false positives — the conditions
   are lazy runtime dispatch (`requiring-resolve` under
   `#?(:cljs nil :default …)`); kondo reads the nil branch and calls
   the condition constant, but the dispatch provably resolves at
   runtime (the glj member's media-server and watcher ARE
   media-impl code reached through this path, observed 2026-11-22).
   Each site carries the reason as a comment at the form.

## Step 3 RESOLUTION (2026-11-22, ~22:20 ET)

**Defect (re-derived, provenance below): the fleet template's
`:sort-ns-references? true` made the "pure formatting" pass a
semantic change, and the demo broke.**

- Control: pristine e096566, reduced hosts — files became :new and
  the coordinator planned all 10 jobs (`{:planned 10}`) within ~2 min
  of run start. (It still failed :index-complete later: planned jobs
  were never claimed and the 90 s watchdog aborted — a second,
  separate stall that also affects pristine sources on this box;
  recorded honestly, not attributed to the format pass.)
- Formatted (b52ceaf): progress stayed {} in two runs — the :new /
  planning stage was never reached.
- Form-tree diff (reader-normalized, regexes stringified) between
  pristine and b52ceaf: the ONLY diffs in all 21 files were :require
  ORDER inside the ns forms of 10 files (cohost.pool /
  cohost.hosts.auto sorted out of first position). Nothing else.
- Fix: sources restored from f5a077e, .cljfmt.edn deviation above,
  `bb fmt` re-run (commit 07d4fac). **Form-tree diff vs pristine is
  now ZERO on all 21 files** — the pass is pure formatting, proven.
  `bb fmt-check` exits 0.
- Confirmation boot on the repaired tree (reduced hosts): reached
  `{:new 10}` then `{:planned 10}` — stage-for-stage the control's
  trajectory, the stages the sorted tree never reached. The format
  pass is behaviorally exonerated; full-hosts run in flight.

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
