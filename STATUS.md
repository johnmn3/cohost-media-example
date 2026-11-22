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

| Edit                                           | replace exit    | repair == cljfmt fix | 2nd repair no-op | kondo before → after          |
| ---------------------------------------------- | --------------- | -------------------- | ---------------- | ----------------------------- |
| start.cljc unused-import (drop BodyPublishers) | 0               | YES byte-for-byte    | YES              | 6e/29w → 6e/28w, finding gone |
| phash.cljc redundant-nested-call               | 0               | YES byte-for-byte    | YES              | info finding gone; file 0e/0w |
| start.cljc redefined-var (display? split)      | 0 (×2 replaces) | YES byte-for-byte    | YES              | 6e/28w → 6e/27w, finding gone |

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
3. **Two `#_{:clj-kondo/ignore [:unused-private-var]}` site
   suppressions** (start.cljc page-mime, seat-handler). Reason:
   verified false positives — both vars ARE used, but only inside
   reader-conditional forms, and kondo 2026.10.26's
   unused-private-var does not count references inside `#?` forms.
   Isolated repros (2026-11-22): a private var used only inside a
   defn nested in `#?(:clj (do …))` is flagged; a private fn called
   only from a `#?` arm is flagged. The other two vars in this class
   (av-jolt C-NB-STREAMS, coordinator settled?) were true positives
   — grep-total one occurrence each — and were DELETED, not
   suppressed.
4. **Thirteen `#_{:clj-kondo/ignore [:unused-binding]}` defn
   suppressions** (av native-fn/cli-transcode!/cli-pcm!, media
   impl-fn, boot-proc proc?, meta run!, start getprop/spawn-child!/
   seat-handler/start-browser-seat!/start-node-seat!/sha256/
   browser-check!). Reason: verified false positives of the same
   family — every flagged binding IS used, in a `:clj`/`:default`
   arm; kondo's unused-binding does not count a reference that
   appears in only some `#?` arms (a binding used in EVERY arm, e.g.
   start's getenv, is not flagged — the discriminator). The class's
   four true positives were FIXED in code: gateway start! and member
   serve! dropped unused `:as` bindings, start seat-handler dropped
   its unused `atoms` param (+ call site), start check renamed its
   unused `run` param to `_run`.
5. **`.clj-kondo/config.edn` additions + one hook file** (deviation
   from the fleet template's bare version pin, proposed in
   RETRO.md): `:lint-as {cohost.pool/deftask clojure.core/defn}` and
   an analyze-call hook for `jolt.ffi/defcfn`
   (`.clj-kondo/hooks/jolt.cljc`) — defcfn's type-only arg vectors
   (with a `:&` varargs marker) are not defn-shaped, so a hook
   rewrites it to a def of an unknown-returning fn of matching
   arity. Together these took unresolved-symbol from 99 errors to
   10, and made kondo analyze bodies it previously skipped.
6. **Nine `#_{:clj-kondo/ignore [:unresolved-symbol]}` site
   suppressions** for the remaining 10 errors (start slurp/Throwable/
   Exception/spit/alter-var-root/read-line, av-jolt Throwable/
   with-open, page js-obj). Reason: kondo lints every .cljc under
   BOTH language passes, but these files are single-language in
   practice — start and av-jolt never run as ClojureScript (their
   unconditioned JVM/host core names fail the cljs pass), and the
   page never runs as clj (js-obj fails the clj pass). Each site is
   commented with its direction.
7. **Twelve entry-level `#_{:clj-kondo/ignore [:unused-namespace]}`
   discards** in ns forms (av ×3, boot-fs, boot-proc, meta ×2,
   start json, tasks ×3, member tasks). Reason: every flagged alias
   is used, but only inside `#?` arms (grep counts recorded in the
   campaign notes); member's tasks require is additionally a
   load-effect registration require (its deftask vars register in
   every member realm at load; serve! names it in :requires) —
   removing it would silently unregister the app's tasks. Reason
   comments sit at each ns form.
8. **Seven `#_{:clj-kondo/ignore [:unused-binding]}` discards** on
   tasks.cljc deftask forms (meta, phash, loudness, thumb, sheet,
   tags, waveform — 13 bindings): all used in exactly one `#?` arm.
9. **Six `#_{:clj-kondo/ignore [:unresolved-namespace]}` discards**
   (page ×5 forms covering js/pool/tasks/em/h/str/reagami, av-jolt
   System). Reason: the page's requires live under `#?@(:cljs …)`
   in its ns form, which kondo's cljc pass does not register (and
   reagami is an Eve-side cljs dep outside this repo's lint
   classpath); av-jolt's System is the jolt host's class surface.
   Kondo reports this linter once per alias at its first use, so one
   discard per alias suffices — verified empirically.

## Splint exceptions (step 4, splint campaign)

Fixed in code (20 of 30 current findings): eq-zero, filterv,
into-literal ×2, let-when, let-if (core), prefer-clj-string,
plus-one ×2, apply-str ×2 (start), not-some-pred ×2, prefer-condp,
loop-empty-when ×3 (rewritten as `while`).

Disabled at site with reasons (10):

- `lint/empty-loop-in-fn` ×5 (start ×4, page ×1): the loops sit in
  ANONYMOUS watcher fns; loop/recur is the constant-stack recursion
  and direct self-recursion would overflow. (Placement note: the
  discard must precede the fn form, not the loop — verified on a
  scratch file.)
- `style/prefer-clj-math` ×4 sites (r128 log10 + peaks, tasks
  loudness): `Math/` interop is the one spelling that runs on every
  host these portable files target; clojure.math has no cljs
  counterpart under that name.
- `style/useless-do` ×1 (start): the `do` groups several defs under
  one `#?(:clj …)` arm — load-bearing, not useless.
- `naming/conversion-functions` ×1 (page send-to-page!): not a
  conversion fn — it sends a message TO the page.
- `lint/let-if` ×1 (coordinator): the binding is used in the
  condition AND both branches; if-let cannot express the shape.
- `style/apply-str` ×1 (phash): the file is deliberately
  dependency-free (hand-rolled cosine); str/join would add a
  require.

## Carve campaign (step 4) — DONE

Carve at census: 18. After the kondo campaign it reported 28 (the
deftask vars became visible to its analysis; the two kondo-deleted
vars dropped off). Dispositions, each grep-verified across the repo
AND the composed eve/cohost trees:

- DELETED (7, no caller anywhere, no name-resolution reference):
  start/alive? (superseded by babashka.process/alive?, which the
  same file uses), supervise/children, state/control,
  domain/open-rungs, phash/groups (work uses groups-from-edges),
  boot-fs/parent, boot-fs/canonicalize.
- KEPT via `.carve/ignore` (21, reached BY NAME at runtime):
  tasks/* ×12 (pool registry dispatch), page
  runtime-main/probe-main/view-main/init (shadow :init-fn + quoted
  thread entries), av-jolt transcode-job!/pcm-job! (native-fn quoted
  symbols), meta/run! (requiring-resolve), member
  node-state/node-serve! (seat calls by name in the realm).
- Format note: carve reads `.carve/ignore` as EDN
  (`edn/read-string "[%s]"`) — `#` comments crash it
  ("No dispatch macro for:"); comments must be `;;`.
- `bb carve-report` now exits 0.

## Gates (step 6) + docs (step 7)

- **Pre-commit hook**: `.githooks/pre-commit` (cljfmt check +
  clj-kondo on staged .clj/.cljs/.cljc files, repo configs). Enable
  per clone: `git config core.hooksPath .githooks` (done in this
  clone). The full gate stays `bb lint`.
- **CI**: the repo has NO CI home (no `.github/`) — nothing added;
  inventing one is out of lane scope.
- **lint-docs leg** adopted from templates v2: `bb lint` now depends
  on lint-docs (`dprint check`) as well.
- **Gate check on the splint batch** (the lint-clean ≠ compiling
  lesson): reduced demo boot (`bb demo --hosts bb,jvm,glj,jolt`) on
  the post-splint tree reached `{:planned 10}` — all ten files
  planned, same as the formatted-tree run; the while/condp/inc
  rewrites boot clean.
- **dprint 0.50.2** (GitHub release binary) with pinned `dprint.json`:
  plugins `https://plugins.dprint.dev/markdown-0.19.0.wasm`,
  `https://plugins.dprint.dev/json-0.21.1.wasm`,
  `https://plugins.dprint.dev/toml-0.7.0.wasm`; markdown textWrap
  maintain. Excludes: the untracked eve/ + cohost.cljc/ sibling
  trees, target/, node_modules, .clj-kondo/ (cache), census-raw/
  (the untouched census evidence), package-lock.json
  (machine-generated). Docs formatted as their own commit;
  `dprint check` exits 0. (CENSUS.md's Markdown was normalized by
  the pass; its data is unchanged and the raw evidence in
  census-raw/ is excluded and untouched.)

## Templates v2 sync + AGENTS.md (coordinator steer, Nov 22 evening)

- **AGENTS.md** copied byte-identical from
  `templates/AGENTS.md` (verified by diff). The repo had NO
  pre-existing agent-instruction files (no AGENTS.md / CLAUDE.md),
  so nothing was merged.
- **bb.edn** fleet section matches templates v2: `lint-docs`
  (`dprint check`) present and `lint` depends on it; task paths
  adapted to this repo (`src page/src`) as before.
- **Config drift vs templates** (checked by diff at the tip):
  `.splint.edn` identical; `.cljfmt.edn` differs ONLY by the
  documented `:sort-ns-references? false` deviation (exception #1,
  RETRO item 1); `.clj-kondo/config.edn` differs ONLY by the
  documented additions (`:lint-as` deftask + the defcfn hook,
  exception #5, RETRO item 3). No other drift.
- **Environment note**: a session cycle this evening partially
  wiped `/root` (SSH known_hosts, parts of `/root/.m2`). Recovery
  was the documented one — SSH config restored from the durable
  copy + host key re-accepted; the missing Maven artifacts for
  the splint and carve dep trees were re-seeded by curl from
  Clojars / repo.maven.apache.org into both `/home/hatch/m2repo`
  and `/root/.m2/repository` (per-artifact "Could not transfer …
  from central" loop until resolution converged). No silent
  workarounds; the tools and pins never changed.

## Census → zero accounting (exit criteria)

| Tool      | Census                                                         | Final          | Delta accounted                                                                                                                                                                                                                                                                      |
| --------- | -------------------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| clj-kondo | 99 err · 67 warn · 38 info (204)                               | 0 · 0 · 1 info | every class fixed in code, taught via config (deftask/defcfn), or suppressed at site with a written reason — STATUS exceptions #2–#9; the 1 remaining info is tasks' unused-excluded-var, BY DESIGN (the ns excludes clojure.core/meta to define its own), left visible deliberately |
| Splint    | 32 (30 at campaign start; 2 had been kondo-batch side effects) | 0              | 20 fixed in code; 10 disabled at site with reasons (STATUS splint exceptions)                                                                                                                                                                                                        |
| Carve     | 18 (28 at campaign start)                                      | 0              | 7 dead vars deleted (grep-verified); 21 name-reached entry points in `.carve/ignore` with reasons; 2 already deleted during the kondo campaign                                                                                                                                       |
| cljfmt    | 21 of 22 files unformatted                                     | 0              | format pass (redone without ns sorting — see step 3); `bb fmt` twice = second run no-op, verified at the tip                                                                                                                                                                         |

Exit criteria, verified at the branch tip:

- `bb lint` exits 0 (all five legs: kondo, splint, carve,
  fmt-check, lint-docs).
- The demo boots: reduced-hosts runs on the formatted tree, the
  post-splint tree and the final tip all reached `{:planned 10}`
  (all ten files planned), stage-identical to the pristine-source
  control. End-to-end completion remains blocked by the
  pre-existing pin/box claim-stall, control-proven on pristine
  sources (step 3 verdict) — not a lane change.
- Census → zero delta accounted line by line (table above +
  the per-class sections).
- RETRO.md banked (12 proposed template changes with evidence).
- Integrated pipeline trial banked (step 6 section): clj-replace
  → clj-paren-repair → clj-kondo on 3 real edits + the exit-4
  exercise; repair output == `cljfmt fix` byte-for-byte on all
  trial files; second repair pass a byte no-op.

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
  pass is behaviorally exonerated.
- Full-hosts run on the repaired tree (22:33–22:38): all six hosts
  started, page check attempted, all 10 files found, all 10 jobs
  planned — then the SAME claim-stage stall as the pristine control
  (node/browser members still booting when the 90 s watchdog fired;
  report `{:ok false, :checks {:index-complete false,
  :browser-library-and-requeue false}}`). Step 3 verdict: **the app
  boots and runs its machinery on the formatted tree identically to
  pristine sources at every stage this box lets either reach**;
  end-to-end completion is blocked by a pre-existing pin/box
  condition (member startup vs the demo's own 90 s watchdog),
  control-proven, not by any lane change.

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
