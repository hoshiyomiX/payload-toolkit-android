# Changelog

## [5.9.0] — 2026-05-19

### Fixed

- **Hook was silent — errors invisible** — v5.8.0 hook redirected all output to `/dev/null`, making it impossible to diagnose why the skill failed to install after a platform reset. Changed to structured logging to `$HOME/.stellar-boot.log` with automatic rotation (last 500 lines). Every hook execution now leaves a trace.

- **Empty SKILL.md not detected** — The install check only tested `[ ! -f SKILL.md ]` (file exists) but not `[ ! -s SKILL.md ]` (file is non-empty). If a platform reset left a zero-byte SKILL.md, boot.sh would report "Skill files OK" and skip installation. Now detects both missing AND empty SKILL.md, forcing reinstall in either case.

### Added

- **Health check fallback in hook** — After the fast boot completes, the hook verifies that SKILL.md is both present and non-empty. If not, it runs a full (non-fast) boot.sh as a fallback. This catches the case where `--fast` mode copies from a stale or corrupted source.

- **Git staging after install (Part B of Proposal C)** — After successfully installing skill files, boot.sh now runs `git add skills/stellar-frameworks/` in the project directory. The platform creates `repo.tar` from the working tree at pre-stop, so staged files get baked into the next session's restore. After one successful session, `repo.tar` contains the latest version — eliminating the stale v5.3.0 fallback even if the hook fails.

- **Log rotation** — Hook appends to `$HOME/.stellar-boot.log` and trims to last 500 lines after each run. Prevents unbounded log growth across sessions.

### Changed

- **Hook rewritten with 3-phase pipeline** — Old: `clone-if-missing; git pull; boot --fast >/dev/null 2>&1`. New: `clone-if-missing; git pull; boot --fast >>$LOG 2>&1; health-check || boot >>$LOG 2>&1; rotate-log`. Three phases instead of one, with structured logging throughout.

- **`MINIMUM_VERSION` bumped to 5.9.0** — Ensures stale snapshots with v5.8.0 or earlier will trigger git pull even in `--fast` mode.

### Why

Investigation into why "Skill stellar-frameworks tidak tersedia" revealed three compounding issues: (1) the hook silently swallowed all errors via `/dev/null`, making diagnosis impossible; (2) an empty SKILL.md file passed the install check, causing boot.sh to skip reinstallation; (3) after every platform reset, `repo.tar` contained a stale v5.3.0 version that would persist until the hook successfully ran — if the hook failed for any reason, the stale version was permanent.

The fix layers three defenses: logging makes failures visible (Part A), git staging ensures `repo.tar` always has the latest version after one successful session (Part B), and the health check catches any remaining gap between fast boot and actual file readiness. Together, these eliminate every identified failure path without requiring root access or official skill registration.

### Files Modified

boot.sh (hook rewrite + install check + git staging), setup.sh (hook sync), SKILL.md (version), README.md (version history), CHANGELOG.md.

### Resilience Architecture

```
Session start:
  ┌─ repo.tar extract → skills/ (latest if previous session succeeded)
  ├─ official_skills extract → skills/ (stellar not registered — no effect)
  ├─ bash_profile sourced → hook runs:
  │   ├─ Phase 1: clone-if-missing + git pull + boot --fast (logged)
  │   ├─ Phase 2: health check [ -s SKILL.md ] || boot (full reinstall)
  │   └─ Phase 3: log rotation (keep 500 lines)
  └─ boot.sh install → git add skills/ (bakes into NEXT repo.tar)
```

| Layer | Mechanism | Catches |
|-------|-----------|---------|
| Hook (Layer 1) | clone + pull + fast boot + log | Missing repo, outdated repo, silent failures |
| Health check (Layer 2) | `[ -s SKILL.md ] || boot` | Empty/corrupted SKILL.md, fast boot failure |
| Git staging (Layer 3) | `git add skills/` after install | Stale repo.tar version on next session |
| Structured log | `$HOME/.stellar-boot.log` | Post-hoc diagnosis of any failure |

## [5.8.0] — 2026-05-19

### Fixed

- **Fatal: platform reset wipes project directory → skill becomes invisible** — Platform resets can wipe `/home/z/my-project/` entirely, destroying both the `skills/stellar-frameworks/` install directory and the `$HOME/.bashrc` auto-heal hook. This left the skill permanently unavailable until manual re-installation, and worse — every `Skill(command="stellar-frameworks")` call was a silent no-op because the skill was not in `available_skills`.

### Changed

- **Git repo migrated from project-internal to `$HOME` path** — `TARGET_DIR` changed from `$PROJECT_ROOT/stellar-frameworks` to `$HOME/.stellar-frameworks-repo`. This path survives platform resets that wipe `/home/z/my-project/` because `$HOME` is not part of the project directory. The old path was inside the project directory, meaning every platform reset destroyed both the installed skill files AND the git repo needed for recovery.

- **Auto-heal hook gains clone-if-missing fallback** — Hook line changed from `cd $TARGET_DIR && git pull` to `[ -d $TARGET_DIR/.git ] || git clone $REPO_URL $TARGET_DIR; cd $TARGET_DIR && git pull`. If the repo directory is missing entirely (full wipe), the hook clones from GitHub before attempting to pull. This is the nuclear recovery option: even if both the project directory AND `$HOME` are wiped, the hook can recreate everything from the remote repository.

- **Old repo path auto-migration** — boot.sh now checks if the repo exists at the old path (`$PROJECT_ROOT/stellar-frameworks`) and automatically moves it to the new path (`$HOME/.stellar-frameworks-repo`). This prevents data loss during upgrade from pre-v5.8.0.

- **`STELLAR_REPO_PATH` env var** — `TARGET_DIR` can be overridden via `$STELLAR_REPO_PATH` for custom installations.

### Why

This is a fatal bug, not a feature request. The entire session was running without the skill framework active because `Skill()` was silently doing nothing — the skill was not registered in `available_skills`. Every phase machine, delivery report, and traceability ID produced during this session was generated by me directly following the SKILL.md content I read via `Read()`, not through the `Skill()` mechanism. While the output quality was the same (I had full context), this defeats the purpose of having a self-healing skill system.

The root cause was a single-point-of-failure architecture: both the recovery mechanism (git repo) and the trigger mechanism (auto-heal hook) lived in paths that platform resets destroy. Moving the repo to `$HOME` and adding clone-from-GitHub fallback eliminates this single point of failure.

### Files Modified

boot.sh (rewrite of path logic + hook), setup.sh (path sync), SKILL.md (version), README.md (quick start + session persistence + version history), CHANGELOG.md.

### Path Architecture

| Component | v5.7.0 (broken) | v5.8.0 (fixed) |
|-----------|-------------------|-----------------|
| Git repo | `$PROJECT_ROOT/stellar-frameworks/` | `$HOME/.stellar-frameworks-repo/` |
| Install target | `$PROJECT_ROOT/skills/stellar-frameworks/` | `$PROJECT_ROOT/skills/stellar-frameworks/` (unchanged — platform constraint) |
| Hook files | `$HOME/.bashrc`, `.bash_profile`, `.profile` | Same (unchanged) |
| Hook logic | `cd repo && git pull; boot.sh` | `clone-if-missing; cd repo && git pull; boot.sh` |
| Survives project reset? | NO (repo inside project dir) | YES (repo in $HOME) |
| Survives full wipe? | NO | YES (clone-from-GitHub fallback) |

## [5.7.0] — 2026-05-18

### Added

- **Post-Activation Protocol** — new section in SKILL.md placed immediately after Activation banner (high-attention zone). Defines a 4-step execution sequence: (1) Load Phase Intelligence — read `procedure/phases.md` before any task output, (2) Classify — determine complexity tier, task type, and continuation status, (3) Confirm Activation — output a structured status block showing classification results, (4) Enter the Phase Machine — begin SPECIFY or IMPLEMENT if continuation detected.

### Changed

- **Phase References table gains "When to Read" column** — each phase now has explicit guidance on when to load its artifact template and knowledge files (e.g., "Start of SPECIFY", "On error detection"). This prevents the common failure mode where agents acknowledge template existence but never actually read them.

- **Phase State Machine reference strengthened** — passive reference ("definitions are in phases.md") replaced with active cross-reference ("the same file the Post-Activation Protocol asks you to read first"). Reinforces the loading chain.

- **Activation section gains transition directive** — one-line bridge between the activation banner and the protocol section: "Follow it before producing any task output — it takes only a few seconds and ensures the phase machine runs correctly."

### Why

The most persistent compliance failure observed in practice: agents load the skill, acknowledge the activation banner, and then proceed with generic task handling — skipping phases, ignoring templates, producing delivery reports as decoration rather than evidence. The root cause is structural: SKILL.md had no execution sequence. The framework described WHAT it does but not WHAT THE AGENT SHOULD DO AFTER LOADING IT. The Post-Activation Protocol fills this gap by placing imperative instructions in the highest-attention zone (lines 17-49), using theory-of-mind explanations for WHY each step matters rather than heavy-handed compliance language. This follows the skill-creator principle: "explain why things are important in lieu of heavy-handed musty MUSTs."

Design choices aligned with skill-creator audit criteria:
- **Progressive Disclosure**: Protocol is in SKILL.md (level 2), phase details stay in bundled resources (level 3)
- **Imperative, not MUST-heavy**: Each step explains WHY, not just WHAT — "Without it, the phase machine is a diagram — phases.md is what makes it executable"
- **Lean**: Protocol adds ~35 lines; total SKILL.md stays at 269 lines (well under 500-line budget)
- **Theory of mind**: Opening paragraph explains the failure mode the protocol addresses — agents treating the banner as decoration

### Files Modified

SKILL.md, README.md, boot.sh, setup.sh.

## [5.6.0] — 2026-05-18

### Changed

- **Terminology overhaul: PCR → Delivery Reports** — The term "Process Compliance Report" (PCR) was contradictory to the v5.0.0 philosophy that explicitly rejected compliance theater. Every reference to PCR has been replaced with plain-language terminology:
  - "Scope PCR" → "Scope Commitment" — the pre-implementation contract output at end of PLAN
  - "Delivery PCR" → "Delivery Report" — the post-implementation record output at end of DELIVER
  - "Compact PCR" → "Compact Report" — single-line format for Simple tasks
  - "Minimal PCR" → inline `☄️ PASS` — one-line for non-coding tasks
  - "Full PCR" → "Delivery Report" — the full block for Standard/Complex tasks
  - "PIVOT" → "Pivot" — consistent sentence case
  - "DELTA Scope" → "Scope Drift" — clearer about what it tracks

- **Block format changes** — Scope Commitment now uses `☄️ COMMIT [Standard]` header. Delivery Report uses `☄️ REPORT [Standard]`. Minimal tasks use `☄️ PASS | Evidence: ...` with no tier label. Compact Report uses `Drift: NONE` instead of `Delta: NONE`.

- **Section renamed** — "Process Compliance Report (PCR v2)" → "Delivery Reports" in SKILL.md. "Complexity Tiers & PCR Format" → "Complexity Tiers & Report Format" in phases.md.

- **Zero acronyms** — No parent acronym. "Scope Commitment" and "Delivery Report" are self-explanatory without reading docs. This aligns with the framework's design principle: give the LLM tools it wants to use, not compliance mandates.

### Why

"Process Compliance Report" sounded like an ISO 9001 audit artifact — the exact kind of compliance theater the v5.0.0 philosophical reset explicitly rejected. The LLM reading this file is not complying with regulations; it's choosing to use useful tools. The new names describe what each output actually is: a commitment before building, and a report after building. No jargon, no acronyms, no theater.

### Files Modified

SKILL.md, procedure/phases.md, procedure/decision-trees/error-resolution.md, procedure/templates/problem-spec.md, procedure/templates/implementation-plan.md, procedure/templates/incident-report.md, README.md. Historical CHANGELOG entries preserved as-is (they document what the terminology was at time of writing).

## [5.5.1] — 2026-05-18

### Fixed

- **Version sync failure in boot.sh** — header comment and post-install banner still referenced v5.4.8 despite SKILL.md being v5.5.0. Both now correctly show v5.5.1.
- **Version sync failure in setup.sh** — done banner still referenced v5.4.8. Now correctly shows v5.5.1.
- **Incident report template missing Pivot Assessment section** — Field guidance referenced a "Pivot Assessment" section as REQUIRED for Approach Failure classifications, but the template markdown block had no such section. Agents following the template would never produce it. Added a formal Pivot Assessment section between Root Cause Analysis and Proposed Fix, with fields for classification, pivot signal, fallback availability, fallback viability, new approach, and user approval.
- **setup.sh version check used fragile grep** — Replaced `grep -q "v5.5.0"` (string match, breaks if version format changes) with semantic version extraction via `grep -oP 'version:\s*\K...'` matching the YAML frontmatter field. Now reports the actual version found on mismatch.
- **SKILL.md description over-trigger** — Description was 603 chars, keyword-stuffed, and explained HOW the framework works instead of WHEN to activate. Rewritten to 218 chars focusing on trigger conditions: "Activates on every task: coding (features, bugs, refactoring, scripts), documents, charts, data processing, or complex planning." Maintains trigger keyword coverage while reducing noise.

### Changed

- **SKILL.md: Phase Gate Protocol condensed** — Reduced from 12 lines (full explanation + 3-column table + Simple/Complex paragraph) to 8 lines (summary + 2-column table + cross-reference to phases.md). Details remain in `procedure/phases.md` which already has the full gate definitions.
- **SKILL.md: Adaptive Pivot Protocol condensed** — Reduced from 14 lines (intro paragraph + rule + 7-row signal table + pivot flow) to 4 lines (summary + cross-reference to error-resolution.md). Details remain in `procedure/decision-trees/error-resolution.md`.
- **SKILL.md line count reduced** — 246 → 234 lines (~5% reduction). Well within the 500-line budget.
- **Version bump to v5.5.1** — All files synchronized: SKILL.md frontmatter, boot.sh header/banner/MINIMUM_VERSION, setup.sh header/banner/version-check, README.md badge/invoke/version-history.

### Why

Audit via skill-creator revealed: (1) version sync gaps between SKILL.md and shell scripts, (2) template/guidance mismatch where the incident report told agents to fill a Pivot Assessment but the template didn't have one, (3) ~12 lines of duplicated content in SKILL.md that already exists in full form in phases.md, (4) description was 603 chars explaining HOW instead of WHEN — causing over-trigger noise.

## [5.5.0] — 2026-05-18

### Added

- **Scope PCR (pre-implementation commitment)** — New PCR variant output at end of PLAN phase (Standard/Complex). Commits to approach, fallback, scope boundaries (IN/OUT), step count, and risk level before IMPLEMENT begins. The delivery PCR's DELTA field measures any deviation from this commitment, making scope drift visible and traceable.

- **Fallback Approach field** — Implementation plan template now requires a fallback approach (1-2 sentences describing what to do if the primary approach fails). This feeds the Adaptive Pivot Protocol — when an Approach Failure is detected during IMPLEMENT, the agent checks this field first before inventing a new approach.

- **Scope Boundary field** — Implementation plan template now requires explicit IN/OUT scope definition. The OUT list prevents scope creep by making exclusions visible before implementation starts.

- **Scope OUT field in problem-spec** — Problem specification template now requires explicit exclusions. Feeds the Scope PCR's Scope OUT and the delivery PCR's DELTA comparison.

- **Phase Gate Protocol** — Phase transitions are now guarded with entry conditions. SPECIFY → PLAN requires all spec fields + SADC. PLAN → IMPLEMENT requires Scope PCR. IMPLEMENT → VERIFY requires self-review pass. VERIFY → DELIVER requires all checks PASS. Gates prevent incomplete output from leaking to the next phase.

- **Adaptive Pivot Protocol** — New error classification: **Approach Failure** (distinct from Code Bug). When the fundamental approach is wrong (50%+ rewrite needed, same error after 2 fix attempts, missing library feature), the agent stops fixing, evaluates the fallback approach, presents a pivot to the user, re-enters PLAN, and re-implements. PIVOT field in delivery PCR records the event.

- **Pivot Assessment in error-resolution** — New section in the error decision tree that runs BEFORE diagnostic paths. Classifies errors as Code Bug vs Approach Failure using concrete criteria, then routes to the appropriate recovery path.

- **Approach Failure classification** — Incident report template now includes Approach Failure as an error category with dedicated Pivot Assessment field.

### Changed

- **PCR v2 format** — Delivery PCR redesigned with structured fields: Steps (completed/planned), Deviations (plan divergence count), Quality (automated checks), PIVOT (approach change tracking), DELTA Scope (commitment comparison). Compact PCR gains `Delta: NONE` field.

- **phases.md** — All phase transitions now have explicit gate conditions. PLAN phase action 3 defines fallback, action 10 outputs Scope PCR. IMPLEMENT action 1f tracks deviations. DELIVER action 8 outputs PCR v2.

- **Error Recovery** — Now starts with classification step (code bug vs approach failure) before any fix attempt. Approach failures route to PLAN (not VERIFY).

- **Return Phase Decision table** — Now includes Classification column. IMPLEMENT errors can return to PLAN (pivot) instead of only VERIFY or SPECIFY.

### Why

The original PCR was purely post-mortem — it only reported what happened after the fact, with no commitment beforehand. This made scope drift invisible (you can't measure deviation from a commitment that was never made). The Adaptive Pivot Protocol addresses the "stubborn agent" failure mode: when an approach is fundamentally wrong, the agent should detect it early and switch strategies instead of burning context on failed fix attempts. Phase Gates ensure each phase produces valid output before the next phase consumes it, preventing error compounding.

## [5.4.8] — 2026-05-18

### Changed

- **dev.sh is now persistent (unkillable)** — Server wrapped in `while true; do ...; sleep N; done` loop. If the python3 process is killed (OOM, signal, crash), it auto-restarts after 1 second. Next.js projects restart after 2 seconds. The popup preview on :3000 is no longer dependent on process survival — it will always come back.

- **Removed PID file mechanism** — The `.zscripts/.dev-server.pid` file and all PID tracking logic removed from both `boot.sh` and `setup.sh`. The PID file was overengineering: with the while-loop auto-restart, the PID changes on each restart cycle, making file-based tracking unreliable. Duplicate prevention now relies solely on the port guard (`ss -tlnp | grep :3000`) at the top of dev.sh — if port 3000 is occupied, dev.sh exits immediately.

- **Dropped Caddy proxy dependency from boot.sh concern** — boot.sh no longer references Caddy's :81 → :3000 proxy chain in its logic. The popup preview serves directly on :3000. Whether Caddy proxies it or not is the platform's concern, not boot.sh's.

### Technical Notes

- **Why while-loop over process supervisor**: No systemd, no respawn config available in sandbox. The while-loop is the simplest self-restart mechanism available. `exec` was replaced with direct command (no `exec`) so the loop continues after the server process exits.
- **Port guard window**: There is a ~1 second window between server death and restart where port 3000 is free. If boot.sh runs during this window, it would launch a second dev.sh instance. However, the port guard in dev.sh prevents the second instance from starting a server — it would just enter the while loop and wait. The first instance's loop would then bind to the port. Net effect: no duplicate servers.
- **Next.js behavior**: `bun run dev` already has built-in hot-reload and crash recovery. The while-loop is a safety net for cases where the entire process is killed (not just a module crash).

## [5.4.7] — 2026-05-18

### Fixed

- **Critical: Stale snapshot version persists across sessions** — When a sandbox restores from repo.tar, both the stellar-frameworks REPO and the installed skill files are at the stale version (e.g. v5.3.0). The two-phase hook's Phase 1 (`--fast`) skipped git ops, but since both source and installed were the same stale version, no upgrade was detected. Phase 2 (async) ran the OLD boot.sh from the stale repo, creating a chicken-and-egg problem where the upgrade mechanism itself needed upgrading.

  **Fix: Hook now runs `git pull` BEFORE `boot.sh`.** This ensures the local repo (including boot.sh itself) is updated to the latest version before any boot.sh logic executes. When the repo is already up-to-date, `git pull --ff-only` is nearly instant (~0.1s), so the performance impact is negligible. When the repo is stale, the pull takes ~5s but guarantees the latest version.

- **Removed two-phase hook, replaced with single-phase pull-then-boot** — The two-phase approach (Phase 1: `--fast` sync, Phase 2: async `git pull`) was fundamentally flawed for the stale snapshot case. Phase 2 ran the OLD boot.sh (from the stale repo), and being async meant it might not complete before the agent's first `Skill()` call. The new single-phase hook is simpler, synchronous, and always correct.

### Changed

- **Hook format**: `(cd $TARGET_DIR && git pull --ff-only --quiet 2>/dev/null); bash $TARGET_DIR/boot.sh --fast --install-only >/dev/null 2>&1` — one line, no background process, no Phase 2.
- **boot.sh gains MINIMUM_VERSION guard** — As a safety net for direct invocations (not via hook), boot.sh checks if the local repo version is below a hardcoded minimum and overrides `--fast` to force git pull. This handles edge cases where boot.sh is called manually on a stale repo.

### Technical Notes

- The stale snapshot problem originated from the platform's `git init` + `git add .` in `/start.sh`, which committed v5.3.0 skill files into the outer project's git history (commit `8b0069c`). Even though `.gitignore` now excludes `skills/stellar-frameworks/`, the pre-stop `repo.tar` is created from the working tree (not git HEAD), so any files on disk get snapshotted.
- Chicken-and-egg problem: stale snapshot has old boot.sh → old boot.sh has no MINIMUM_VERSION → old boot.sh doesn't know it's stale → no upgrade. Solution: the HOOK (not boot.sh) does `git pull` first, updating boot.sh itself before execution.
- Performance: `git pull --ff-only` on an already-up-to-date repo is ~0.1s (just a network check). On a stale repo, it's ~5s. This only affects `.bashrc`/`.bash_profile` sourcing, which happens once at session start.

## [5.4.6] — 2026-05-17

### Fixed

- **Critical: Popup preview not starting on fresh installation** — boot.sh v5.4.5 only created `.zscripts/dev.sh` but did not launch it. The platform's `/start.sh` auto-executes dev.sh at session start, but on fresh install, dev.sh doesn't exist yet when `/start.sh` runs (because boot.sh hasn't run yet). Result: port :3000 stayed empty, Caddy `:81` showed 502 Bad Gateway. Fixed by having boot.sh directly launch the server after creating dev.sh, using a PID file (`~/.zscripts/.dev-server.pid`) to prevent duplicate launches across Phase 1, Phase 2, and `/start.sh`.

### Changed

- **dev.sh now guards against duplicate launches** — Added port check at the top of dev.sh: if `:3000` is already occupied, dev.sh exits gracefully instead of crashing with "Address already in use". This prevents noisy errors when both boot.sh and `/start.sh` attempt to launch the server.
- **Popup preview banner updated** — Post-install message now says "LIVE on :3000 (immediate, no restart)" instead of "will be active on next session".

### Technical Notes

- The PID file approach was chosen over simple port checking (`ss -tlnp | grep :3000`) because: (1) it works without root privileges, (2) it survives the brief window between server launch and port binding, (3) it correctly identifies the server process even if something else temporarily binds to :3000.
- Startup sequence on RESTORE (repo.tar with previous session data): `/start.sh` sources `.bash_profile` → Phase 1 hook runs boot.sh → boot.sh creates dev.sh + launches server → `/start.sh` continues → finds dev.sh → tries to launch → dev.sh's port guard exits gracefully → single server instance running. Correct.
- Startup sequence on FRESH install (no previous data): `/start.sh` runs → no `.bash_profile` hook → no dev.sh → skips dev server → later, agent runs boot.sh (manually or via first shell open) → boot.sh creates dev.sh + launches server → popup preview becomes active immediately without restart.

## [5.4.5] — 2026-05-17

### Added

- **Popup preview auto-provider via `.zscripts/dev.sh`** — boot.sh now automatically creates `.zscripts/dev.sh` if it doesn't exist. This enables the platform's popup preview (Caddy :81 → proxy → :3000) without needing fullstack-dev. The dev.sh is smart: if a Next.js project exists (package.json with "next" dep), it delegates to `bun run dev`; otherwise it serves `/download/` as static files via Python http.server. Activates on next session start (platform's start.sh auto-executes dev.sh).

### Changed

- **boot.sh description** — Updated header comment to include "popup preview provider" and clarify the script's scope: skill installer + popup preview enabler.

### Technical Notes

- The `.zscripts/dev.sh` created by boot.sh is idempotent — it's only created if missing, never overwritten (preserves any externally-created dev.sh).
- fullstack-dev's `init-fullstack.sh` detects existing dev.sh and skips tarball download, running dev.sh instead. Since our dev.sh is smart (detects Next.js), this coexistence works: if fullstack-dev has set up a Next.js project, our dev.sh delegates to `bun run dev`. If not, it serves static files.
- To force a clean fullstack-dev setup: `rm .zscripts/dev.sh` then invoke fullstack-dev.

## [5.4.4] — 2026-05-17

### Removed

- **Dev server section from boot.sh** — Entire section (splash deploy, Next.js project bootstrap, dev server startup) removed from boot.sh (was lines 221-383). boot.sh is now a pure skill installer/self-heal with no web development responsibilities. This eliminates 3 critical conflicts with the platform's `fullstack-dev` skill: (1) init-fullstack.sh sabotage via `.zscripts/dev.sh` detection, (2) port 3000 collision, (3) filesystem pollution where boot.sh's minimal Next.js files prevented fullstack-dev's proper tarball extraction.

### Fixed

- **Version stale bug on sandbox reset** — The `--fast` flag skipped all git operations, which meant after a sandbox snapshot restore, the installed skill could never update from the stale snapshot version to the latest remote version. Fixed with **two-phase auto-heal hook**: Phase 1 (sync, ~50ms) runs `--fast` to ensure skill name is in platform cache immediately; Phase 2 (async, ~5-15s) runs without `--fast` to perform `git fetch + pull` and re-copy latest version. Next `Skill()` call reads the updated version from disk.
- **`fa51c75` missing version field** — Historical: the first v5.3.0 commit had no `version:` field in SKILL.md frontmatter, causing boot.sh to read `0.0.0` as the version. Fixed in subsequent commit `a825c6a` (already on remote).

### Changed

- **Auto-heal hook: single-phase → two-phase** — Hook now writes two commands to each init file instead of one. Phase 1 is synchronous (completes before platform scans), Phase 2 runs in background (updates version asynchronously).
- **`--install-only` flag is now a no-op** — Previously controlled dev server skip. Since dev server section is removed, the flag is accepted for backwards compatibility but does nothing.

### Why

Three discoveries drove this release:

1. **fullstack-dev is the platform's official web development handler** — It provides a proper Next.js 16 project with shadcn/ui, Prisma, and all dependencies. boot.sh's minimal Next.js bootstrap (5 deps, no UI framework) was redundant AND harmful: when boot.sh created `.zscripts/dev.sh`, fullstack-dev's `init-fullstack.sh` would detect it and skip its own proper initialization, leaving the user with a broken skeleton instead of a full project.

2. **`--fast` mode created a version trap** — The flag was introduced to avoid race conditions (git fetch delay vs platform scan timing). But the trade-off was permanent: after a sandbox snapshot restore, `--fast` could only copy the stale snapshot version, never pulling the latest. The two-phase approach eliminates this trade-off: skill name is available immediately, version updates happen in background.

3. **`Skill()` reads SKILL.md from disk on each call** — This platform behavior means Phase 2's background update takes effect on the very next `Skill()` invocation. No restart needed, no cache to invalidate.

## [5.4.3] — 2026-05-15

### Fixed

- **Critical: Race condition in .bashrc auto-heal hook** — The .bashrc hook used `&` (background/async) and ran `--install-only` which still performed git fetch/pull (~5-10s network delay). Fixed with `--fast` flag (skip git, ~60ms) + synchronous execution (no `&`).
- **Stale .bashrc hook cleanup** — boot.sh now removes old async hooks (v5.4.2 with trailing `&`) and stale hooks from wrong path (`$PROJECT_ROOT/.bashrc`, v5.4.1 bug).
- **Multi-layer hook redundancy** — Hook now written to 3 init files (`.bashrc`, `.bash_profile`, `.profile`) instead of just `.bashrc`. Sandbox resets may wipe one but rarely all three.

### Changed

- **Post-install message: no restart needed** — Platform reads SKILL.md from disk on each `Skill()` call, NOT from a session-start cache. Updates are effective immediately without restart. Previous versions incorrectly told users to restart.

### Added

- **Mid-session activation via direct file read** — `activate.sh` script for cases where the skill directory doesn't exist yet (before first boot.sh run).

### Why

Three key discoveries drove this release:

1. **Platform reads SKILL.md from disk each time** `Skill()` is called — it does NOT cache content at session start. This was verified by overwriting v5.3.0 → v5.4.3 on disk and immediately getting v5.4.3 from `Skill()`. This eliminates the "must restart" friction entirely.

2. **Sandbox snapshot includes stellar-frameworks v5.3.0** — The platform ships an outdated version in the base image. Fresh sandboxes always start with v5.3.0, which lacks SADC, improved session continuity, and other v5.4.x features. The auto-heal hook upgrades to latest on next shell open.

3. **Single `.bashrc` hook is fragile** — Sandbox resets can wipe `$HOME/.bashrc`. Writing to three init files (`.bashrc`, `.bash_profile`, `.profile`) provides redundancy: at least one typically survives a reset.

## [5.4.2] — 2026-05-15

### Fixed

- **Critical: .bashrc auto-heal hook written to wrong path** — boot.sh and setup.sh wrote the auto-heal hook to `$PROJECT_ROOT/.bashrc` (`/home/z/my-project/.bashrc`), which is **never sourced by the platform**. The platform sources `$HOME/.bashrc` (`/home/z/.bashrc`). This meant the entire self-heal mechanism was non-functional: after sandbox resets, the skill files were wiped and never auto-recovered. Fixed by writing to `$HOME/.bashrc` in both boot.sh and setup.sh.
- **Old wrong .bashrc cleanup** — boot.sh now removes any stale `.bashrc` hook from the project root (`$PROJECT_ROOT/.bashrc`) if it exists from a previous installation.

### Added

- **Post-install restart notice** — boot.sh now displays a clear warning box after fresh install: "Skill installed but NOT yet available in this session. Please RESTART this session to activate stellar-frameworks." The platform loads `available_skills` at session start; skills installed mid-session are invisible until the next session. This prevents user confusion when `Skill(command="stellar-frameworks")` fails immediately after running the one-liner.
- **setup.sh auto-heal hook** — setup.sh now also writes the `$HOME/.bashrc` auto-heal hook, not just boot.sh. Previously only boot.sh configured persistence.

### Why

User reported persistent bug: after running the one-liner in a fresh sandbox and leaving for hours, `stellar-frameworks` disappeared from `available_skills`. The root cause was a one-line path error: `.bashrc` hook was written to `/home/z/my-project/.bashrc` (never sourced) instead of `/home/z/.bashrc` (sourced by platform on shell open). The self-heal mechanism added in v5.4.1 was completely non-functional due to this path mistake.

## [5.4.1] — 2026-05-14

### Added

- **Source Availability & Documentation Check (SADC)** — new mandatory step as the first action (action 1) in SPECIFY phase. Before restating the problem, the agent must research: (1) existing packages/libraries/frameworks that already solve the task, (2) official documentation for the recommended approach, (3) established patterns and best practices. Tier-specific depth: Minimal (skip), Simple (quick check against one source), Standard (full research — search + docs + confirm no wheel reinvention), Complex (deep research — multiple sources, compare approaches, document tradeoffs). New section in SKILL.md with full specification.
- **Source Research field** in problem-spec template — new required field documenting what sources were checked, what was found, and if nothing was found, an explicit statement. "Building from scratch when a library exists is a spec-level defect."

### Changed

- **SPECIFY phase purpose** — updated from "removes ambiguity" to "removes ambiguity — grounded in real sources, not assumptions."
- **SPECIFY phase actions** — renumbered with SADC as action 1 (was implicitly action 0). Problem restatement now explicitly notes it must be "informed by the sources found in step 1."

### Why

The framework had a fatal gap: SPECIFY jumped straight to "restate the problem" without checking if a solution already existed or what the official docs recommended. This caused agents to build from assumptions, use APIs incorrectly, or reinvent existing wheels — leading to massive refactoring when the correct approach was discovered later. SADC closes this gap by making source research the first thing that happens in SPECIFY, before any planning begins. It is to implementation what SSV is to analysis: a freshness check that prevents working from stale assumptions.

## [5.4.0] — 2026-05-13

### Changed

- **No SKIP — only internal**: The "Non-Coding → SKIP" concept is replaced with a **Minimal** complexity tier. All six phases always run for ALL tasks — no exceptions. For non-coding tasks (questions, explanations, recommendations), SPECIFY, PLAN, and VERIFY run internally (the agent thinks through them without producing formal artifacts). IMPLEMENT produces the visible output. This means the framework's participation is binary: always on. The dial that turns is ceremony, not presence.
- **Minimal PCR format**: New compact format `☄️ PCR [Minimal] Phases→internal : PASS | Evidence: <one-line result>` replaces the old `☄️ PCR [Non-Coding] SPECIFY→SKIP PLAN→SKIP IMPLEMENT→PASS VERIFY→SKIP` format. No phase is labeled SKIP — all phases ran, just internally.
- **Task Type Awareness table**: Non-Coding row changed from `SKIP` across SPECIFY/PLAN/VERIFY to `Internal (identify question)`, `Internal (plan approach)`, `Internal (self-check)`. Explicit statement added: "No phases are ever skipped."
- **phases.md Task Type Adaptation**: Non-Coding column added to the adaptation table. Traceability IDs now explicitly scoped to Simple/Standard/Complex tiers (Minimal does not use them).
- **Skill description**: Rewritten to emphasize "without exception" and "complexity adapts, participation never skips." Removes all SKIP language from the trigger description.
- **Complexity Tiers**: Four tiers now — Minimal, Simple, Standard, Complex. Minimal is the floor, not a bypass.

### Why

The v5.3.2 approach of marking phases as "SKIP" for non-coding tasks created an ambiguity: does SKIP mean "the phase didn't run" or "the phase ran but produced no output"? This matters because a phase that truly doesn't run means the agent didn't think through the problem before answering. By making all phases always run (even if internally), the framework ensures structured thinking happens for every interaction — the difference is just whether the thinking is visible.

## [5.3.2] — 2026-05-13

### Added

- **Non-Coding task type** — new row in Task Type Awareness: questions, explanations, and recommendations now trigger the framework with SPECIFY, PLAN, and VERIFY all SKIPPED. IMPLEMENT does the actual work (answering, explaining). DELIVER outputs a compact `[Non-Coding]` PCR. This gives every interaction a traceable record, not just coding tasks.
- **Non-Coding PCR format** — single-line compact format: `☄️ PCR [Non-Coding] SPECIFY→SKIP PLAN→SKIP IMPLEMENT→PASS VERIFY→SKIP | Evidence: <one-line result>`.

### Changed

- **Skill description: universal activation** — framework now triggers for ALL tasks, not just coding. Description rewritten to cover coding tasks (full phases) and non-coding tasks (SKIP phases with PCR traceability). "Core workflow that structures ALL tasks through a phase machine" replaces "Core coding workflow."
- **Activation banner** — added "Universal" to feature list.

## [5.3.1] — 2026-05-13

### Changed

- **Skill description rewritten for aggressive triggering** — replaced abstract jargon ("deterministic coding workflow with phase state machine, traceability IDs, artifact templates, and structured verification") with action-oriented trigger description (~75 words). Explicitly enumerates task types (features, bugs, refactoring, scripts, debugging, code generation) and includes universal catch-all closing phrase. Manual eval score: 5/20 → 20/20. The phase machine is now described as non-optional ("always runs — adapts verbosity to complexity but never skips") per user requirement that all code tasks use the framework.

### Fixed

- **setup.sh version confirmation message** — grep pattern was updated to match the new version string but the confirmation message was not, causing it to report "Version 5.3.0 confirmed" when checking for v5.3.1.

## [5.3.0] — 2026-05-11

### Added

- **Task Type Awareness** — new section in SKILL.md and phases.md extending the phase machine beyond coding tasks. Four task types (Coding, Document, Visualization, Data Processing) each have adapted SPECIFY/PLAN/IMPLEMENT/VERIFY behaviors. Traceability IDs apply to all types.
- **Multi-Skill Orchestration (Skill Chain)** — PLAN phase now supports defining skill invocation sequences with SKILL-level Traceability IDs (SKILL-001, SKILL-002, ...). Enables orchestrating multi-skill workflows (e.g., web-search → charts → PDF).
- **TodoWrite Integration** — PLAN phase recommends syncing IMPL-XXX steps to the platform's native TodoWrite tool for real-time progress visibility.
- **Compact Verification Template** — verification-report.md now includes a 5-row compact variant for Simple tasks, alongside the existing full template for Standard/Complex.
- **AI/SDK Error Diagnostic Path** — new category in error-resolution.md covering SDK invocation failures, rate limiting, timeout, image generation errors, and web search failures.
- **Phase-Transition Memory Reminders** — memory-template.md now defines a one-line memory check at each phase transition, not just IDLE. Ensures memory stays active throughout the entire phase machine.
- **Completion Signal** — DELIVER phase now explicitly references the platform's `Complete` tool for web development tasks.
- **boot.sh auto-bootstrap** — when `.zscripts/dev.sh` is missing, boot.sh automatically creates it and initializes a minimal Next.js project (package.json, tsconfig, Tailwind v4, layout, page). No separate `fullstack-dev init` step needed. `--install-only` flag skips dev server entirely.
- **Session Continuity** — new section in SKILL.md and continuation check in IDLE phase (phases.md). Prevents the LLM from regenerating proposals, plans, or specifications the user has already seen. Continuation signals (user approves plan, references previous output, follow-up question) cause SPECIFY and/or PLAN to be skipped. PCR block gains `Continuation` field (NEW/YES) and SKIP status for bypassed phases.

### Changed

- **boot.sh version check** — replaced weak `grep "Phase State Machine"` with semantic version comparison. Fixes the critical bug where v5.2.0 features were not installed because the check passed for both v5.0.0 and v5.2.0.
- **boot.sh dev server is now optional** — missing `.zscripts/dev.sh` no longer causes `exit 1`. boot.sh auto-creates it and bootstraps a Next.js project if needed. Dev server failure is the only condition that returns exit 1.
- **boot.sh knowledge file paths** — updated to match new `knowledge/universal/` and `knowledge/platform/` directory structure.
- **Knowledge directory restructured** — split into `knowledge/universal/` (architecture, conventions, error-patterns) and `knowledge/platform/zai-sandbox.md`. Universal files are portable across platforms; platform file contains z.ai-specific constraints. All internal references updated.
- **Skill description shortened** — removed verbose trigger phrases from frontmatter (was ~600 chars, now ~120 chars). Improves skill triggering accuracy on the platform.
- **PCR block enhanced** — added `Tier` field (Simple/Standard/Complex) and `Continuation` field (NEW/YES) with SKIP status for bypassed phases.
- **Memory budget increased** — MEMORY.md soft budget raised from ~2,000 to ~3,000 characters to accommodate meaningful preference entries.
- **Error resolution references updated** — all knowledge file references now point to `knowledge/universal/` and `knowledge/platform/zai-sandbox.md`.

### Fixed

- **boot.sh auto-update failure** — the `NEED_INSTALL` check used `grep -q "Phase State Machine"` which matched both v5.0.0 and v5.2.0, preventing auto-update from v5.0.0 to v5.2.0. Now uses version tag comparison.

### Rebranded

- **stellar-coding-agent → stellar-frameworks** — project, skill, directory, and all internal references renamed. GitHub repo URL, `Skill()` invocation command, install paths, and documentation all updated. Historical CHANGELOG entries preserved as-is (they reference the old name at time of writing).

## [5.2.0] — 2026-05-10

### Added

- **Memory directory architecture** — replaced flat `memory.md` with a structured `memory/` directory containing evergreen files (`MEMORY.md`, `decisions.md`, `incidents.md`) and dated session logs (`YYYY-MM-DD.md`). Inspired by Memweave's design: plain Markdown files as source of truth, filename convention determines lifecycle (evergreen vs dated).
- **Bounded memory budget** — MEMORY.md has a ~2,000 character soft budget with agent-driven curation. When exceeded, DELIVER flags it for consolidation. Inspired by Hermes's philosophy: let the LLM decide what to keep/evict rather than relying on mechanical eviction algorithms.
- **Rich session summary** — Standard/Complex tasks now capture decisions, context, and caveats in addition to the compact task/outcome format. Preserves decision rationale across sessions for pre-compaction knowledge extraction.
- **Complexity Tiers & PCR Format** — new section in phases.md defining Simple (compact PCR, abbreviated artifacts), Standard (full PCR), and Complex (full PCR + detailed evidence). The phase machine always runs; what changes is verbosity, not rigor.
- **Compact PCR for Simple tasks** — single-line format `☄️ PCR [Simple] SPECIFY→DELIVER : PASS | Evidence: ... | Defects: 0` replaces the full 6-row block for trivial tasks.

### Changed

- **Skill description expanded** — added explicit trigger phrases ("build", "implement", "fix bugs", "refactor", "audit", "follow the process", "use stellar", "phase machine", "structured workflow") and auto-abbreviate clause for trivial fixes. Skill-creator audit score improved from 2/10 to 8/10 for triggering.
- **DELIVER phase** — action 1 now writes to `memory/YYYY-MM-DD.md` (dated file, append-only). New action 2 checks MEMORY.md budget. Rich format for Standard/Complex captures decisions, context, caveats.
- **Error Handling** — incident logging now writes to `memory/incidents.md` instead of a shared `memory.md` Patterns section.
- **IDLE phase** — action 3 now reads `memory/MEMORY.md` with graceful handling when `memory/` directory doesn't exist yet.

### Fixed

- **boot.sh path resolution** — added `PROJECT_ROOT` detection so the repo can live as a subdirectory of `/home/z/my-project/`. All paths (page.tsx, dev.sh, database, logs) now resolve to the project root, not the repo directory.
- **setup.sh install path** — `INSTALL_DIR` now uses `$PROJECT_ROOT/skills/` instead of repo-relative path, ensuring the skill installs to the platform's load path.

## [5.1.0] — 2026-04-19

### Added

- **"When Active" section** — placed in high-attention zone (after Activation, before Preview Bootstrap). Defines what a "task" is (code/file changes vs conversation), connects task-start to phase declaration, and connects task-end to PCR output. Addresses the failure mode where the LLM loads the skill, understands the framework, but skips it entirely because the PCR block was in the low-attention tail of SKILL.md.
- **Cross-reference** in Process Compliance Report section pointing back to "When Active."

### Changed

- **Abbreviation guidance** — "abbreviate when they don't" → "abbreviate when the task is simple, but never skip entirely." The v5.0.0 permissive language was too loose; the LLM interpreted "simple task" as "zero phases." Adding a floor: SPECIFY+PLAN combined into one paragraph, PCR always output.

### Why

Commit edb092c (boot.sh auto-update) was implemented without following the framework — no SPECIFY, no PLAN, no PCR. Root cause: the PCR block at lines 89-103 of SKILL.md was in the LLM's low-attention zone, and the v5.0.0 language gave too much room to rationalize skipping. The fix moves the completion signal to high-attention territory (lines 17-21) and adds a concrete abbreviation floor. This does not guarantee compliance (nothing in a text file can) but makes the tools visible when they're needed.

## [5.0.0] — 2026-04-13

### Philosophy Change

v5.0.0 is a philosophical reset based on an honest audit of the framework's effectiveness. The audit found that compliance enforcement language ("Do not skip phases", "mandatory", "must") has no measurable effect on LLM behavior — the same LLM follows or ignores the framework regardless of how strongly it's worded. Meanwhile, the tools that work (traceability IDs, templates, SSV) work because they're useful, not because they're mandatory.

**Design principle**: Stop telling the LLM what it MUST do. Start giving it tools it WANTS to use.

### Removed
- **Coexistence with fullstack-dev** — 18-line section that the user explicitly rejected as "nonsense" because it doesn't solve the persistence problem. The framework is technology-agnostic; whether fullstack-dev is active or not is the LLM's concern, not this file's.
- **Implementation Rules** — Duplicated knowledge/constraints files when standalone and conflicted with fullstack-dev when coexisting. The Phase References table and constraints/ directory already serve this purpose.
- **Complexity Tiers** — A classification table that prescribed workflow abbreviations based on file count. In practice, the agent already adapts naturally. Formal tiers added rules that were sometimes followed and sometimes ignored, with no quality difference.
- **Scope section** — Five rules about what the framework "does not" do. Unnecessary boundary declaration — the framework's scope is self-evident from its content.
- **QA Attestation → Process Compliance Report (PCR)** — Renamed to be honest about what it is. "QA" implies independent quality assurance; the attestation is self-graded. The honesty note (retained) already acknowledged this, but the name contradicted it.
- **Evidence tiers, status value definitions, delivery gate rules** — Detailed specification of attestation mechanics that added 30+ lines. The attestation block format is self-explanatory; surrounding it with rules didn't improve accuracy.

### Changed
- SKILL.md rewritten: 181 lines → ~95 lines (~48% reduction)
- Activation banner updated to v5.0.0, replaced phase/template counts with feature names
- New "Limitations" section at top: explicitly states what the framework cannot do (guarantee compliance, force behavior, persist across sessions)
- Compliance language removed: "Do not skip phases", "mandatory", "Do not omit" replaced with "use them when they help, abbreviate when they don't"
- Phase descriptions condensed from full paragraphs to single-purpose sentences
- Error recovery section simplified from numbered sub-steps to essential rules
- Git rules retained but shortened — removed redundant decision tree reference when the full tree already exists in the referenced file

### Fixed
- **State diagram inconsistency** — phases.md had error arrow from VERIFY→SPECIFY; SKILL.md had DELIVER→SPECIFY. Consolidated to one canonical version: "On error: stop, diagnose, fix, return to VERIFY" with SPECIFY as the alternative for specification gaps.
- **memory-template.md path mismatch** — Template referenced `~/code/memory.md` but phases.md referenced `skills/stellar-frameworks/memory.md`. Fixed to single canonical path: `/home/z/my-project/skills/stellar-frameworks/memory.md`.
- **phases.md path reference** — Changed `Check memory.md in this skill directory` to avoid future path drift.

### Honest Assessment
This refactor does not solve the persistence problem (impossible within platform architecture). It does not improve compliance rates (nothing in a text file can). What it does is: stop lying about what the framework can do, remove 86 lines of dead weight that diluted attention from the parts that actually work, and make the framework shorter and clearer so the useful tools (traceability IDs, templates, SSV, decision tree) are more likely to be read and used.

## [4.6.0] — 2026-04-13

### Added
- Source State Verification (SSV) — new section in SKILL.md mandating git fetch + comparison before any analysis/audit task on git repositories
- Source State field in problem-spec template — records branch, HEAD SHA, and verification status
- Source integrity check in verification-report Review Checklist
- Stale Local Data error pattern in error-patterns.md ([CRITICAL] severity)
- Stale-data recovery path (#5) in error-resolution decision tree Git section
- Cross-session git state awareness flag in IDLE phase (action 3.5)
- Evidence tiers in QA Attestation — code-creation vs code-analysis/audit tasks have different evidence requirements; analysis tasks must include source state verification

### Changed
- SPECIFY phase: entry criteria now includes source state verification; action 7.5 added for SSV
- VERIFY phase: action 1b added for source integrity check on analysis tasks
- IDLE phase: action 3.5 added for cross-session git state uncertainty flag

### Why
A stale local git clone caused a false-negative audit — the agent analyzed outdated files, claimed 20 applied fixes were absent, and delivered a confidently incorrect report. SSV closes this gap at every level: SKILL.md (mandate), SPECIFY (gate), VERIFY (defense-in-depth), templates (record), knowledge base (pattern recognition), and decision tree (recovery path).

## [4.5.0] — 2026-04-12

### Added
- Coexistence Mode — new "Coexistence with fullstack-dev" section defining how this framework layers with the platform-provided fullstack-dev skill
- IMPLEMENT phase defers technology-specific decisions to fullstack-dev when it is active; falls back to own `constraints/` and `knowledge/` files when standalone

### Why
fullstack-dev persists across sessions (system prompt level) and provides deep Next.js technical expertise. This framework provides orthogonal process governance. Rather than duplicating fullstack-dev's technical rules and risking conflicting instructions, the framework recognized fullstack-dev's presence and deferred to it for IMPLEMENT-phase decisions. (Removed in v5.0.0 — user identified this as unnecessary and the section was removed.)

## [4.4.2] — 2026-04-11

### Changed
- QA Attestation is now required after every task, not just coding tasks
- Non-coding tasks (conversation, questions, feedback) mark phases as N/A but still output the attestation block

### Why
The Activation section had an escape hatch: "If the user's request is not a coding task, the phase machine does not apply." This allowed skipping the attestation entirely on non-coding tasks — the exact failure mode the user wanted to detect. Making it mandatory for all tasks means: no attestation = framework was not followed.
