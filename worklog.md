---
Task ID: 1
Agent: Main Agent
Task: Fix CI release build failure and simplify APK to single-purpose repack flow

Work Log:
- Cloned repo and pulled latest (15 commits ahead of last session)
- Diagnosed CI: Debug APK builds ✅, Release APK (signed) fails ❌
- Root cause: `shrinkResources true` + `proguard-android-optimize.txt` causing release build failure
- Examined all source files: 6 Kotlin files, layout, themes, strings, manifest
- Fixed build.gradle: shrinkResources false, proguard-android.txt
- Rewrote PayloadBridge.kt: added buildOutputFileName(), default device/fingerprint
- Rewrote MainActivity.kt: removed mode chips, single repack flow
- Rewrote activity_main.xml: clean 3-section layout (partitions/compression/output)
- Updated strings.xml: simplified for repack-only UI
- Updated version to 2.1.0 (versionCode 4)
- Committed as c4036b8 (263 insertions, 671 deletions = -408 lines)

Stage Summary:
- Release build fix: shrinkResources disabled, safer ProGuard config
- App simplified: removed 5-mode complexity, now single-purpose partition repacker
- Smart output naming: flashable_<partitions>_v16_<compress>.zip
- Net code reduction: 408 lines removed
- PAT expired — commit ready locally, needs push with new token
