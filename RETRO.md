# RETRO — cohost-media-example lane, syntax/formalization-1

Proposed fleet-template changes, each with the evidence that produced
it. The coordinator owns the templates; this lane proposes. All
claims re-derived at this lane's pin (branch tip, tools below);
provenance per item.

Toolchain at the pin: clj-kondo 2026.10.26, cljfmt 0.16.6,
Splint 1.25.0, carve 0.3.5, babashka 1.13.229/1.13.230,
clojure-mcp-light 0.2.2, dprint 0.50.2.

## 1. `:sort-ns-references?` must default OFF in the fleet .cljfmt.edn

The template ships `:sort-ns-references? true`. Sorting `:require`
lists changes namespace LOAD ORDER, which is semantics in Clojure,
not formatting. Proof: the format pass reordered `cohost.pool` /
`cohost.hosts.auto` out of first position in 10 files; the demo's
coordinator then never planned a job (two runs, 90 s watchdog
abort), while a control run on pristine sources planned all 10
jobs. A reader-normalized form-tree diff between pristine and
formatted trees showed require order was the ONLY difference.
With sorting off, the form-tree diff is ZERO on all 21 files and
the demo matches the control stage for stage. This repo carries
the deviation (`:sort-ns-references? false` + reason) until the
template changes. A formatter must never reorder requires.

## 2. `.glj` files are invisible to the whole stack

`src/cohost_media/media_impl.glj` (the glojure member) is linted by
nothing: kondo covered 21 of 22 source files, and splint / carve /
cljfmt likewise have no `.glj` mapping. Fleet decision needed: a
kondo `--config` extension mapping or an explicit documented
exclusion — silence is the worst option, because the file holds
real code (the media server the other members talk to).

## 3. Kondo teaching for cohost/Eve defining macros belongs in the template story

Portable cohost/Eve codebases define vars through macros stock
kondo cannot see; every such var reads as an unresolved symbol
(99 errors here) and bodies go unanalyzed. Two teachings, both
now in this repo's `.clj-kondo/config.edn`, proposed as template
material for any repo on cohost.pool / jolt:

- `:lint-as {cohost.pool/deftask clojure.core/defn}` — deftask is
  defn-shaped.
- An analyze-call hook for `jolt.ffi/defcfn`
  (`.clj-kondo/hooks/jolt.cljc` in this repo). defcfn is NOT
  defn-shaped — its arg vectors are FFI type specs (`[:pointer
  :int]`, `:&` for C varargs); lint-as defn produced 168 syntax
  errors. The hook rewrites the call to an arity-faithful defn
  (unknown return type; a literal `nil` body poisoned downstream
  type inference).

## 4. Kondo's unused-\* family miscounts `#?` reader-conditional references

In portable `.cljc`, a binding or var referenced in only SOME
`#?` arms is reported unused; a defn nested in `#?(:clj (do …))`
has its body references invisible to unused-private-var; a call
from inside a `#?` arm does not count as a use. Discriminator: a
binding used in EVERY arm is not flagged. Minimal repros were
built during this lane (scratch k3.cljc / k4.cljc). 33 of this
repo's 37 unused-binding findings and 2 of 4 unused-private-var
findings were this false positive. Until kondo counts conditional
references, portable codebases will carry per-site ignores; the
fleet guide should name the pattern so reviewers expect them.

## 5. Dual-language .cljc passes create phantom unresolved symbols

Kondo lints every `.cljc` under both clj and cljs passes.
Single-language files collect errors in the wrong pass: JVM host
names (start.cljc, av-jolt.cljc) fail the cljs pass; the page's
`js-obj` fails the clj pass. 9 site ignores here. Template-level
guidance (per-file `:config-in-ns` or documented site ignores)
would beat every repo rediscovering it.

## 6. The fleet needs a babashka floor

Stock bb 1.3.190 breaks the stack twice over: this repo requires
≥ 1.13.220, and clojure-mcp-light 0.2.2 fails entirely under it
(its bundled timbre v6 lacks `set-config!` — a proven defect with
repro). Splint separately needs bb ≥ 1.12.205 (narcis lane).
Propose one pinned floor in the template README: babashka 1.13.x
(this lane ran 1.13.229/1.13.230 for everything).

## 7. Carve analyzes with its own stale kondo pod

Carve 0.3.5 bundles clj-kondo 2023.01.20 as a pod — three years
behind the fleet pin. Its findings are therefore computed by a
different analyzer than `bb lint`'s kondo leg (here: the deftask
vars only became visible to carve after unrelated edits). Either
pin a carve whose pod tracks the fleet kondo, or document that
carve's verdicts are advisory-shaped and its keep-list
(`.carve/ignore`) is the fleet's mechanism for name-reached entry
points. Also: carve reads `.carve/ignore` as EDN — `#` comments
crash it ("No dispatch macro for:"); comments must be `;;`. That
belongs in the template's example ignore file.

## 8. Splint mechanics worth one line each in the fleet guide

- The bb task must require `noahtheduke.splint` INSIDE the task
  body (top-level require in bb.edn fails).
- `#_{:splint/disable [rule]}` must precede the form the
  diagnostic ATTACHES to, which is not always the flagged form:
  `lint/empty-loop-in-fn` attaches to the enclosing fn, so a
  discard before the loop does nothing (scratch-verified: disable
  before loop still flagged; before fn or defn, clean).
- `style/prefer-clj-math` fights portable `.cljc`: `Math/` interop
  is the one spelling that runs on clj + jolt + cljs;
  `clojure.math` has no ClojureScript counterpart under that name.
  Propose disabling this rule fleet-wide for `.cljc` paths, or
  scoping it to `.clj` only. 4 false positives here.

## 9. cljfmt 0.16.6 is not installable via Maven on restricted networks

The Maven coordinate 404s through this fleet's egress proxy on
repo1.maven.org, repo.maven.apache.org and Clojars alike (all
proven during install). The working route is bbin GIT coords:
`bbin install io.github.weavejester/cljfmt --git/tag 0.16.6`.
The template README's install section should carry the git-coords
line as the primary route, Maven as the fallback.

## 10. clj-replace near-match behavior is environment-sensitive

The skill's pinned deref case (`@a` vs `(deref a)`) exits 4
(near match) in the skill's own verification, but exits 2
(no match) under babashka 1.13.229 here. Fail-closed held in
every case (file untouched until retried with the printed exact
text), so this is a documentation fix, not a safety issue: the
skill's exit-code table should note that 2 vs 4 for reader-macro
forms varies by environment.

## 11. mcp-light's bundled cljfmt is not the fleet cljfmt

clojure-mcp-light 0.2.2 bundles cljfmt 0.15.5; the fleet pin is
0.16.6. In this lane's integrated-pipeline trial (3 real edits +
the exit-4 exercise), clj-paren-repair's output equalled the
`cljfmt fix` binary's output byte-for-byte on every trial file —
because the files were already 0.16.6-formatted. The fleet binary
must stay the format of record; repair is the structure tool.
(Corroborates the narcis lane's v2 note.)

## 12. A lint-clean tree is not a compiling tree — gate after every batch

Twice in this campaign the dangerous change wore a formatting
costume (item 1) or a lint costume. This lane booted the demo
after the format pass, after the splint batch, and at the tip;
the narcis lane's identical lesson is already in the templates.
Propose the gate-after-batch rule be written into the campaign
plan itself, with the demo/boot/test command named per repo at
adoption time.
