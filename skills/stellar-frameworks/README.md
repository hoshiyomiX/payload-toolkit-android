# Stellar Frameworks

Structured development framework for GLM — a phase state machine with traceability IDs, phase gates, scope commitment, and adaptive complexity.

## Quick Start

```bash
[ -d ~/.stellar-frameworks-repo ] || git clone https://github.com/hoshiyomiX/stellar-frameworks.git ~/.stellar-frameworks-repo
bash ~/.stellar-frameworks-repo/boot.sh
```

Subsequent sessions auto-heal via shell hook — no manual steps needed.

## Invoke

After install, the skill triggers automatically on every task. Manual activation:

```
Skill(command="stellar-frameworks")
```

## Version History

| Version | Date | Summary |
|---------|------|---------|
| 5.11.0 | 2026-05-21 | Major refactor: repo-wide version sync (setup.sh desync fixed, root README updated, single-source version extraction, hook sync) |
| 5.10.0 | 2026-05-21 | Skill-creator audit: dead refs, dead asset, description optimization, README created |
| 5.9.0 | 2026-05-19 | Hook silent error fix, empty SKILL.md detection, health check fallback, git staging for repo.tar |
| 5.8.0 | 2026-05-19 | Fatal: platform reset wipes project dir; git repo migrated to $HOME, auto-heal hook gains clone-if-missing |
| 5.7.0 | 2026-05-18 | Post-Activation Protocol, Phase References table |
| 5.6.0 | 2026-05-18 | Terminology: PCR -> Delivery Reports / Scope Commitment |
| 5.5.0 | 2026-05-18 | Scope Commitment, Fallback Approach, Phase Gate Protocol, Adaptive Pivot Protocol |
| 5.4.0 | 2026-05-13 | No SKIP phases; Minimal tier for trivial tasks |

See CHANGELOG.md for full history.

## Architecture

```
stellar-frameworks/               (repo root)
├── boot.sh                        # Self-heal installer (clone, symlink, hook, index.html)
└── skill/stellar-frameworks/
    ├── SKILL.md                   # Skill definition (activation + framework reference)
    ├── memory-template.md         # Memory system templates & storage rules
    ├── procedure/
    │   ├── phases.md              # 6-phase state machine definitions + gates
    │   ├── templates/             # Output templates (problem-spec, implementation-plan, incident-report, verification-report)
    │   └── decision-trees/        # Error resolution + pivot assessment
    ├── knowledge/
    │   ├── platform/              # Z.ai sandbox constraints
    │   └── universal/             # Architecture, conventions, error patterns
    ├── constraints/               # Code standards + type safety rules
    └── CHANGELOG.md               # Detailed version history
```

## Phase Machine

```
IDLE → SPECIFY → PLAN → IMPLEMENT → VERIFY → DELIVER
  ↑                                        │
  └──── Error Recovery ◄───────────────────┘
```

## License

MIT
