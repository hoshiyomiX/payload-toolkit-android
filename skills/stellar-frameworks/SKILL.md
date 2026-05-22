---
name: stellar-frameworks
description: "Activates on every task without exception: coding (features, bugs, refactoring, scripts), documents (reports, proposals, DOCX, PDF), charts and visualizations, data processing, complex multi-step planning, or even simple questions. Provides a phase machine with traceability IDs, phase gates, and scope commitment that adapts complexity automatically. Use this skill whenever the user asks to build, fix, analyze, create, plan, or process anything — the framework runs internally for trivial tasks and fully for complex ones. Web development (Next.js, UI) is delegated to fullstack-dev; this framework wraps the phase machine around it."
---
<!-- VERSION SYNC: on bump, update (1) metadata section below, (2) activation banner below, (3) boot.sh header/banner/MINIMUM_VERSION, (4) setup.sh header/banner/version-check, (5) root README.md badge/invoke/file-structure/version-history, (6) skill/README.md version-history, (7) CHANGELOG.md -->

## Metadata

- **version**: 5.11.0

## Activation

```
☄️ STELLAR · v5.11.0 · ACTIVE
   Phase State Machine · Traceability IDs · Phase Gates · Scope Commitment · Adaptive Pivot · SSV · SADC · Memory · Continuity · Universal
```

**Before any task output, complete these steps in order:**

**Step 1 — Bootstrap (~50ms, self-heal):**
```
bash /home/z/my-project/boot.sh --fast
```
Idempotent — prints "Skill files OK" if everything exists, clones repo if missing.

**Step 2 — Load phase intelligence:**
Read `procedure/phases.md`. Also load the artifact template and knowledge files matching the current task from the Phase References table below.

**Step 3 — Classify:**
Determine: complexity tier (Minimal/Simple/Standard/Complex), task type (Coding/Document/Visualization/Data Processing/Non-Coding), and continuity (check preceding assistant message — if user references, approves, or follows up, this is a continuation; see Session Continuity below).

**Step 4 — Confirm activation:**
```
☄️ STELLAR · v5.11.0 · ACTIVE
   Phase: IDLE → SPECIFY
   Complexity: [tier] | Task Type: [type] | Continuation: [NEW / YES]
```

**Step 5 — Enter phase machine:**
Begin SPECIFY (or IMPLEMENT if continuation detected). All phases always run.

---

This framework structures ALL work as a phase machine. It activates for every task — coding or not — without exception. What changes between tasks is the complexity tier, not whether the framework participates. Coding tasks get full phases with Traceability IDs and formal verification. Non-coding tasks (questions, explanations, recommendations) get Minimal tier — all phases still run, but SPECIFY, PLAN, and VERIFY happen internally (the agent thinks through them without outputting formal artifacts). Only IMPLEMENT produces visible work. Every task, regardless of type, gets a delivery report recording that the framework was followed.

## Limitations

This framework is text in a skill file. It cannot guarantee compliance, force behavior, or persist across sessions. The LLM reading this may follow it closely, loosely, or not at all depending on context, attention, and task complexity. The QA Attestation is self-graded — useful as a confidence signal, not independent verification. The user is the final judge of quality.

## Phase State Machine

```
IDLE → SPECIFY → PLAN → IMPLEMENT → VERIFY → DELIVER
  ↑                                        │
  └──── Error Recovery ◄───────────────────┘
```

On error: assess (code bug or approach failure?), fix or pivot, return to VERIFY. See Adaptive Pivot Protocol.

| Phase | Purpose |
|-------|---------|
| IDLE | Receive task, classify complexity |
| SPECIFY | Research sources, restate problem, identify constraints |
| PLAN | Create implementation steps with Traceability IDs |
| IMPLEMENT | Write code, reference Traceability IDs |
| VERIFY | Run checks, trace edge cases, confirm Traceability IDs satisfied |
| DELIVER | Present results with attestation |

Phase definitions, entry/exit criteria, and transition rules are in `procedure/phases.md` — the same file Step 2 of Activation asks you to read first.

## Session Continuity

The most common failure mode in multi-turn sessions: the LLM re-derives a proposal or plan from scratch instead of continuing from the previous output. This wastes context, introduces inconsistencies, and frustrates users.

**Rule**: Before entering any phase, check if the user's message is a continuation of previous work. To detect this, read the immediately preceding assistant message — if the user's reply references, approves, corrects, or follows up on that output, it is a continuation. **After context truncation**, read `worklog.md` — the last entry contains the exact task state snapshot needed to resume.

| Signal | Type | Action |
|--------|------|--------|
| User references previous output ("apply all 10", "fix point 3", "proceed") | Continuation | Skip SPECIFY+PLAN → go directly to IMPLEMENT |
| User approves a proposal/plan ("yes", "go ahead", "do it") | Continuation | Skip SPECIFY+PLAN → go directly to IMPLEMENT |
| User asks a follow-up question ("what about X?") | Continuation | Skip SPECIFY → answer within current phase context |
| User provides new requirements mid-task | New task | Restart from SPECIFY with updated requirements |
| User invokes Skill() with new instructions | New task | Full phase machine from IDLE |
| Context compression boundary with ongoing task | **Continuation** | **Read `worklog.md` last entry, resume from recorded phase** |

**Continuation shortcuts**:

```
Continuation + user approves plan  → skip SPECIFY + PLAN → IMPLEMENT
Continuation + user asks follow-up → skip SPECIFY → answer in current phase
Continuation + user reports error  → skip SPECIFY + PLAN → Error Recovery → VERIFY
Continuation + context truncation   → read worklog.md → resume from recorded phase
```

This is not optional — regenerating proposals the user already approved is a correctness bug, not a style preference.

### Worklog Continuity Protocol

Every DELIVER phase appends a **Task State Snapshot** to `worklog.md`. This is the primary continuity mechanism — not the conversation history, not memory files. The worklog is the single source of truth for "what was I doing last."

**On DELIVER (always, all tiers)**: Append to `/home/z/my-project/worklog.md`:

```
---
last_phase: DELIVER
task: <one-line description of what was accomplished>
complexity: <Minimal|Simple|Standard|Complex>
task_type: <Coding|Document|Visualization|Data Processing|Non-Coding>
files_modified: <comma-separated list or "none">
next_step: <what the user should do next, or "IDLE - awaiting input">
```

**On context truncation (IDLE)**: Read the last `---` block from `worklog.md`. If the task description matches what the user is asking about, skip SPECIFY+PLAN and resume from the recorded phase.

Why this works: Context compression discards the middle of conversations but never touches files on disk. The worklog is always current because DELIVER writes it before the compression boundary is reached.

## Task Type Awareness

This framework is not limited to coding tasks. The phase machine adapts to the task type. All phases always run — what changes is what each phase produces and how much ceremony surrounds it:

| Task Type | SPECIFY | PLAN | IMPLEMENT | VERIFY |
|-----------|---------|------|------------|--------|
| **Coding** (bug fix, refactor, scripts) | Problem spec | Code steps + Traceability IDs | Write code | Lint, type check, tests |
| **Document** (report, proposal, DOCX, PDF) | Content outline | Section plan + structure | Generate document | Format check, completeness |
| **Visualization** (charts, diagrams, dashboards) | Visual requirements | Data mapping + layout | Generate chart | Visual accuracy, data integrity |
| **Data Processing** (ETL, analysis, transform) | Data spec | Transform pipeline | Write script | Output validation |
| **Non-Coding** (question, explain, recommend) | Internal (identify question) | Internal (plan approach) | Answer / explain / recommend | Internal (self-check) |

No phases are ever skipped. Non-coding tasks use **Minimal** complexity tier — SPECIFY, PLAN, and VERIFY run internally (the agent thinks through them without producing formal artifacts). IMPLEMENT does the visible work. DELIVER outputs a compact report. No Traceability IDs, no templates. See Complexity Tiers in `procedure/phases.md`.

## Phase References

| Phase | Artifact Template | Knowledge Files | When to Read |
|-------|-------------------|-----------------|--------------|
| SPECIFY | `procedure/templates/problem-spec.md` | `knowledge/universal/architecture.md`, `knowledge/platform/zai-sandbox.md` | Start of SPECIFY |
| PLAN | `procedure/templates/implementation-plan.md` | `knowledge/universal/conventions.md` | Start of PLAN |
| IMPLEMENT | (code/document/chart output) | `constraints/code-standards.md`, `constraints/type-safety.md` | Start of IMPLEMENT (coding tasks) |
| VERIFY | `procedure/templates/verification-report.md` | `knowledge/universal/error-patterns.md` | Start of VERIFY |
| Error Recovery | `procedure/templates/incident-report.md` | `procedure/decision-trees/error-resolution.md` | On error detection |

## Source State Verification (SSV)

Before analyzing or auditing a git repository, verify data freshness:

1. `git fetch` to sync remote references
2. Compare local HEAD against `origin/<branch>`
3. If behind, `git pull` or `git checkout <branch>` after fetch
4. If referencing a specific commit, verify it exists in history
5. Only proceed after SSV passes

SSV is required after cross-session boundaries or when previous sessions involved git operations. Skip SSV for purely creative tasks with no git involvement.

## Source Availability & Documentation Check (SADC)

Before planning any implementation, verify that the approach is grounded in real sources — not assumptions. The most expensive refactor is the one caused by building something that already existed or using an API incorrectly because the docs weren't checked first.

**Rule**: In the SPECIFY phase, before restating the problem, the agent must research available solutions and official documentation.

1. **Existing solutions** — Are there packages, libraries, frameworks, or SDK methods that already solve this? Search before building.
2. **Official documentation** — What does the official doc say about the recommended approach? Read it, don't guess.
3. **Known patterns** — Are there established patterns, best practices, or examples for this type of task?

| Complexity | SADC Requirement |
|-----------|-------------------|
| **Minimal** | Skip — knowledge questions don't need source research. |
| **Simple** | Quick check — verify the approach against at least one source (docs, search, or existing code). |
| **Standard** | Full research — search for existing solutions, read official docs, confirm no wheel reinvention. |
| **Complex** | Deep research — multiple sources, compare approaches, document tradeoffs, present alternatives. |

SADC is the first action in SPECIFY. The problem specification must reference the sources checked. If no existing solution is found, state that explicitly — "searched npm/PyPI/docs, no existing package found" is a valid result. Building from scratch when a library exists is a spec-level defect.

## Adaptive Pivot Protocol

On every error, classify it as **Code Bug** or **Approach Failure** before attempting a fix. Approach Failure signals (50%+ rewrite needed, same error after 2 attempts, missing library feature, data model change) trigger a pivot to the fallback approach defined in the Scope Commitment. See `procedure/decision-trees/error-resolution.md` for the full Pivot Assessment criteria and recovery flow.

**Pivot flow**: Error detected → classify → if Approach Failure: re-enter PLAN with fallback (from Scope Commitment) or new approach → present to user → re-implement → re-verify. Record in the Pivot field of the delivery report.

## Error Recovery

1. **Stop** — do not continue past errors
2. **Classify** — code bug or approach failure? (see Adaptive Pivot Protocol above)
3. If code bug: document the error (incident report template), fix root cause, return to VERIFY
4. If approach failure: re-enter PLAN, evaluate alternatives (Scope Commitment fallback first), present pivot to user, re-implement
5. Ask the user before any action with side effects (git changes, file deletions, destructive operations)

Git rules (overrides defaults):
- `git fetch` and inspect before `git pull` — if remote diverged, stop and ask
- No `git rebase`, `git reset`, `git push --force`, or `git merge` without explicit user instruction
- If git is blocked by infrastructure, stop all git operations and inform the user

Full decision tree: `procedure/decision-trees/error-resolution.md`.

## Phase Gate Protocol

Phase transitions are guarded — each gate has an entry condition. See `procedure/phases.md` for full gate definitions.

| Gate | Condition |
|------|----------|
| SPECIFY → PLAN | All problem-spec fields filled, SADC complete |
| PLAN → IMPLEMENT | Scope Commitment output (Standard/Complex) |
| IMPLEMENT → VERIFY | Self-review pass, all IMPL steps done |
| VERIFY → DELIVER | All verification items PASS |

Any deviation from the Scope Commitment must appear in the delivery report's Scope Drift field.

## Delivery Reports

Two structured outputs bookend implementation: **Scope Commitment** (end of PLAN, before IMPLEMENT) and **Delivery Report** (end of DELIVER). The commitment says what will be built. The report says what was actually built and whether it matches.

### Scope Commitment (output at end of PLAN)

Used by Standard and Complex-tier tasks. Simple tasks scope internally (no formal output). This is the implementation contract — the delivery report will be measured against it.

```
☄️ COMMIT [Standard]
├─ Approach       : <primary approach, 1-2 sentences>
├─ Fallback       : <alternative if primary fails, 1 sentence>
├─ Scope IN       : <what's included>
├─ Scope OUT      : <what's explicitly excluded>
├─ IMPL Steps     : X (IMPL-001 to IMPL-XXX)
└─ Risk           : LOW / MEDIUM / HIGH
```

### Compact Report (Simple)

```
☄️ REPORT [Simple]
SPECIFY→DELIVER : PASS | Evidence: <one-line result> | Defects: 0 | Drift: NONE
```

### Delivery Report (Standard / Complex)

```
☄️ REPORT [Standard]
├─ Continuation : NEW / YES
├─ IMPLEMENT     : PASS
│  ├─ Steps      : 4/4
│  ├─ Deviations : 0
│  └─ Quality    : lint PASS, tsc PASS
├─ VERIFY        : PASS
│  ├─ Checks     : 3/3
│  └─ Edge Cases : 2/2
├─ Pivot         : NONE
├─ Scope Drift   : NONE
└─ Outcome       : PASS

Evidence: [concrete results]
Defects found and fixed: 0
```

| Field | Meaning |
|-------|---------|
| Continuation | Whether this task continued from previous work |
| Steps | IMPL steps completed vs planned (e.g. 4/4) |
| Deviations | Times implementation diverged from plan (0 = clean) |
| Quality | Automated checks during implementation |
| Pivot | NONE, or details of approach change (trigger, from, to) |
| Scope Drift | NONE, or what changed from Scope Commitment |

If Pivot is not NONE, expand it:

```
├─ Pivot         : YES
│  ├─ From      : <original approach>
│  ├─ Trigger   : <what made us pivot>
│  ├─ To        : <new approach>
│  └─ Re-planned : X steps (IMPL-001 to IMPL-XXX)
```

Pivot is not a failure marker — it's evidence of professional adaptation. An agent that pivots cleanly is more reliable than one that stubbornly forces a broken approach.

### Minimal (non-coding: questions, explanations, recommendations)

```
☄️ PASS | Evidence: <one-line result>
```

All phases ran internally — SPECIFY, PLAN, and VERIFY produced no formal output. Only IMPLEMENT generated visible work. Single-line format.

Self-graded. The evidence requirement makes fabrication harder but cannot guarantee independence.

## Completion Signal

For interactive web development tasks (Next.js, UI components, dashboards), implementation is delegated to fullstack-dev — the DELIVER phase calls the platform's `Complete(project_type="web_dev", summary="...")` tool to finalize. For non-web coding tasks, DELIVER presents output file paths. **In all cases, DELIVER must append a Task State Snapshot to `worklog.md`** — see Worklog Continuity Protocol in Session Continuity above.
