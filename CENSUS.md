# Census — cohost-media-example, before any fixing

Taken at branch `syntax/formalization-1` @ 6609e5a (fleet templates v1
in place), working tree otherwise untouched. This is the baseline the
no-cheats audit compares against. Raw outputs: `census-raw/`.

Tools: clj-kondo 2026.10.26 · cljfmt 0.16.6 · Splint 1.25.0 ·
carve v0.3.5 · bb 1.13.229. Paths linted: `src page/src`
(22 source files: 21 `.cljc` + 1 `.glj`).

## Coverage gap (all tools)

`src/cohost_media/media_impl.glj` is invisible to every checker:
clj-kondo linted 21 files (it has no `.glj` language mapping), and
Splint/Carve report nothing from it either. One of 22 source files is
outside the gate until the fleet decides how `.glj` is covered.

## clj-kondo — exit 3 (errors present)

204 findings: **99 errors · 67 warnings · 38 info**.

| count | level | linter | classification |
|---:|---|---|---|
| 99 | error | unresolved-symbol | see breakdown below — macro/branch resolution, not typos |
| 37 | warning | unused-binding | real |
| 36 | info | redundant-str-call | real (info: does not fail the gate) |
| 12 | warning | unused-namespace | real |
| 8 | warning | unresolved-namespace | reader-conditional aliases (js, pool, tasks, em, h, str, reagami, System) |
| 4 | warning | constant-condition | needs per-site verdict (av.cljc:58,77; media.cljc:17,25) |
| 4 | warning | unused-private-var | real — but see Carve note on entry points |
| 1 | warning | unused-import | real |
| 1 | warning | redefined-var | real |
| 1 | info | redundant-nested-call | real |
| 1 | info | unused-excluded-var | real |

### The 99 unresolved-symbol errors, by mechanism (verified by reading the sites)

- **75 in `av_jolt.cljc`** — `(ffi/defcfn avutil-version "avutil_version" [] :uint32)`:
  `jolt.ffi/defcfn` defines a var per C function; kondo has no model of
  it, so every defined name and every call reads as unresolved.
  Plus `Throwable` in a `:jolt`-branch catch.
- **17 in `tasks.cljc`** — `pool/deftask` (cohost.pool) defines a task
  var per form: `(pool/deftask index [job] ...)`; the task names and
  their `job` bindings read as unresolved without a model of the macro.
- **6 in `start.cljc`** — `slurp`, `spit`, `Throwable`, `Exception`,
  `alter-var-root`, `read-line`: core vars under this file's
  reader-conditional ns/host branches (exact feature interaction to be
  pinned during the fix campaign).
- **1 in `page.cljc`** — `js-obj` inside a non-`:cljs` reader feature
  branch kondo does not map to the cljs language.

Fix route for this class (fix campaign): teach kondo the macros
(`:lint-as` / hooks) as a named, reasoned repo-local config exception
and a fleet-template proposal — never suppression of the findings.

## Splint — exit 1

32 findings:

| count | rule |
|---:|---|
| 5 | lint/empty-loop-in-fn |
| 4 | style/prefer-clj-math |
| 3 | lint/loop-empty-when |
| 3 | style/apply-str |
| 2 | lint/into-literal |
| 2 | lint/let-if |
| 2 | style/plus-one |
| 2 | style/not-some-pred |
| 1 | naming/conversion-functions |
| 1 | style/filter-vec-filterv |
| 1 | lint/let-when |
| 1 | style/redundant-nested-call |
| 1 | style/nested-multiply |
| 1 | style/prefer-condp |
| 1 | style/useless-do |
| 1 | style/eq-zero |
| 1 | style/prefer-clj-string |

## Carve — exit 1 (report mode fails on findings, as the gate requires)

18 unused vars. **Caution recorded before any deletion:** several are
plausible entry points called from outside this repo's call graph —
the page's `runtime-main` / `probe-main` / `view-main` / `init`
(page.cljc), `boot-fs/parent` and `boot-fs/canonicalize` (defined at
36:4 / 93:4, i.e. inside a form, likely protocol impls), and facility
entry points (`meta/run!`, `av-jolt/transcode-job!`, `av-jolt/pcm-job!`)
that pool task bodies may reach by name. Each var gets a provenance
check (grep the whole repo + the composed Eve/cohost trees) before it
is deleted; deleting an entry point would break the demo.

Full list in `census-raw/carve.txt`.

## cljfmt — exit 1

21 of 22 source files have incorrect formatting under the fleet
`.cljfmt.edn` (every `.cljc` file; `media_impl.glj` is not a cljfmt
input either). The format pass is step 3, its own commit.
