# Critique: SPRINT-008-CLAUDE-DRAFT

## Overall Assessment

Strong identification of the main hotspots and good focus on mechanical, low-risk optimizations.
The draft is directionally solid, but several plan details either risk regressions or leave the
largest runtime costs mostly intact.

## High-Priority Findings

1. **Skipping interview broadcasts can break human-gate UX timing**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:90-91`  
The draft proposes skipping `onUpdate()` for `InterviewStarted`/`InterviewCompleted`. In current
flow, those events drive visible log/status changes while a pipeline may be paused waiting on
human input. If skipped, the UI can remain stale until an unrelated later event.
**Fix:** Only skip `CheckpointSaved` initially; keep interview and diagnostics events broadcasted
or coalesced (not dropped).

2. **`hasLog` update only on `StageCompleted` introduces false negatives**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:123-124`, `:384`  
The plan/DoD sets `hasLog` on `StageCompleted` only. Failed stages can still produce `live.log`;
with this approach, failed stages may show no log icon, which is a behavior regression.
**Fix:** Update `hasLog` on `StageFailed` and repair-failure paths too (or compute once on terminal
stage transitions regardless of success/failure).

3. **Largest serialization bottleneck is identified but not actually reduced enough**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:53-55`, `:69-71`, `:427-428`  
The draft keeps rebuilding full `allPipelinesJson()` payloads (including all pipelines and
`dotSource`) on each remaining event. Skipping one event type helps, but this likely will not meet
the sprint success criterion of measurable serialization/broadcast CPU reduction.
**Fix:** Add payload coalescing/debounce and/or dirty-fragment caching in `WebMonitorServer` as a
core phase, not a deferred question.

4. **Fixed HTTP thread pool risks SSE starvation**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:356-366`  
`/events` uses a blocking loop per client. Moving to a fixed-size pool can starve API handlers
when SSE clients consume threads, especially if client count exceeds pool size.
**Fix:** Use a bounded-but-elastic executor strategy (or dedicate separate executors for SSE vs
short-lived HTTP handlers) instead of one fixed pool.

## Medium-Priority Findings

1. **Plan text is not implementation-clean in Phase 2**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:140`, `:173`  
The draft includes exploratory narration (`"Wait —"` / `"Actually simpler"`), which makes the plan
ambiguous.
**Fix:** Collapse to one finalized approach and remove abandoned branches.

2. **Checkpoint optimization only addresses pretty-printing, not context-copy overhead**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:299-310`  
Intent calls out full context copying per stage as a hotspot. The draft changes JSON formatting but
does not plan reduction of snapshot/copy work.
**Fix:** Add a task for snapshot-path optimization in `Context`/`Checkpoint` (fewer conversions,
single-pass map construction).

3. **`ConcurrentLinkedDeque.size` in trim loop is still linear-time traversal**
Reference: `docs/sprints/drafts/SPRINT-008-CLAUDE-DRAFT.md:403`  
This is likely acceptable at small cap, but the stated O(1) framing is not fully true with current
trim logic.
**Fix:** Track log count explicitly (atomic counter) or use a synchronized ring buffer with fixed
capacity.

## Suggested Edits Before Implementation

1. Keep broadcast skipping to `CheckpointSaved` only; do not drop interview updates.
2. Make `hasLog` updates cover both success and failure terminal stage outcomes.
3. Add an explicit `WebMonitorServer` coalescing/caching phase for `allPipelinesJson()`.
4. Rework executor plan to avoid long-lived SSE handlers exhausting fixed threads.
5. Finalize Phase 2 wording into a single concrete implementation path.
6. Extend checkpoint phase to include context snapshot/copy reduction tasks.

## Bottom Line

The draft is a good baseline, but it needs one more tightening pass to avoid UI regressions and to
hit the core performance goal (broadcast/serialization reduction) rather than mostly incremental
wins.
