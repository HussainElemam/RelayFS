# AGENT.md - Agent Operating Instructions

> **ATTENTION AGENT:**  
> This repository is configured for **Spec-Driven Agentic Engineering**.  
> You must strictly follow the protocols defined in the documentation suite before modifying or adding code.

---

## Quick Navigation

1. **Agent Operating Manual:** [`docs/AGENT.md`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/AGENT.md)
   * Engineering principles, test-driven requirements, non-destructive data safety, and execution rules.
2. **Active Roadmap & Phase Tracker:** [`docs/ROADMAP.md`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/ROADMAP.md)
   * **Inspect this file first** to find the current active phase.
3. **Master Product Requirements Document (PRD):** [`docs/PRD.md`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/PRD.md)
   * Vision, requirements, architecture, and operational constraints.
4. **Phase Implementation Guides:** [`docs/phases/`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/)
   * Step-by-step task checklists and verification commands for each individual phase.
5. **Feature Specifications:** [`docs/features/`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/features/)
   * Deep technical specifications for all subsystems.
6. **Architecture Documentation:** [`docs/architecture/`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/architecture/)
   * Detailed architectural designs, IPC mechanics, and algorithms.

---

## The Golden Rule for Autonomous Agents

**Implement ONE phase at a time.**  
Do not attempt to implement the entire project or skip ahead.
1. Read `docs/ROADMAP.md` to identify the active phase.
2. Open `docs/phases/phase_XX_*.md`.
3. Complete only the tasks listed in that phase.
4. Run deterministic tests: `./gradlew testDebugUnitTest`.
5. Update checkboxes in the phase file and `docs/ROADMAP.md`.
6. **STOP and present results to the user for review.**

