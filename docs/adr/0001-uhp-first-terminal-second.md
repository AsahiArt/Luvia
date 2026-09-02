# ADR 0001: UHP structured control is the primary surface; terminal is secondary

Status: accepted (2026-09-02)

## Context

Luvus 0.13.4 (`/Users/misaka/Developer/luvus` `c42b78c`) exposes ~197 UHP methods on
`luvus.sock`. `luvia-host` connects to that socket directly, so the narrow
`allowed_method` allow-list in `src/uhp/gateway.rs` (which only permits focus,
`agent.prompt`, and `terminal.backend.control`) does not apply. Access is governed
by `required_scope` (`src/api/capabilities.rs:271-317`) and the Device's Role scopes
(`read,workspace,agent,terminal,orchestration` for Controller).

With those scopes a phone can, without any PTY stream:

- read an Agent's Transcript (`agent.read`, `dispatch.rs:2584-2604`),
- answer a Blocked Agent (`agent.prompt` atomic submit, `agent.keys` named keys),
- read Diffs and leave / send Review notes (`diff.*`, `diff.note.*`, `dispatch.rs:3392-3810`),
- read and mutate the Task board with `if_revision`,
- read Mission (`mission.snapshot`, 0.13.4+),
- receive 50 typed events (`protocol/uhp/v1/schema/event-catalog.schema.json`).

Terminal observe/control competes for a Host-wide budget of eight streams shared
with the desktop TUI, is byte-level, and cannot tell the phone what the Agent is
doing.

## Decision

1. The phone's primary screens are Agents, Review, and Tasks, built only on
   unary UHP methods and `events.subscribe`.
2. Terminal observe is demoted to a fallback tab inside Agent detail. Terminal
   control (exclusive takeover) is deferred to after v1.
3. Feature availability is gated by `uhp.capabilities.methods`, not by version
   strings, so a 0.13.2 Host degrades gracefully (loses Mission only).
4. The phone never calls: `task.next` (declared read-only, actually claims),
   `agent.send` (non-atomic; use `agent.prompt`), any `admin`/`extensions`
   method, destructive layout methods (`*.close`, `worktree.remove`), or
   TUI-chrome methods (`*.open`, `*.focus`, `diff.navigate`).

## Consequences

- Shared Kotlin gains `readAgent`, `sendAgentKeys`, typed `promptAgent`,
  `missionSnapshot`, `listDiff`/`getDiff`/review-note methods, `gitStatus`/`gitLog`,
  and typed `BusEvent`s for `agent.hook`, `task.*`, `lease.*`.
- `agent.read` is polled on `pane.agent_status_changed` / `terminal.output_ready`
  rather than streamed; latency is event-driven, not frame-driven.
- Host-profile methods (`host.*`, `session.list/status`) are unreachable on the
  socket (`src/api/host.rs:76-81`) and are out of scope until `luvia-host`
  optionally fronts `luvus uhp proxy`.
