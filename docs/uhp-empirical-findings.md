# Empirically verified UHP facts (Luvus 0.13.2, live socket)

Source of truth: probes against a running Luvus at `~/.luvus/luvus.sock` on 2026-08-31.
The raw capability, snapshot, and method-result captures are deliberately not
committed — a live snapshot carries workspace paths and agent names from whichever
machine produced it. Regenerate them against your own instance with
`scripts/verify-e2e.py`, which exercises every method quoted below.

These facts outrank both the vendored `protocol/uhp/v1/` fixtures (which contain only
4 response lines) and any assumption currently encoded in the Luvia client.

## F1. BLOCKER: neither Luvia role can call the two methods the client is built on

`uhp.capabilities.method_contracts` declares:

| method | access | scope |
| --- | --- | --- |
| `session.snapshot` | read | **admin** |
| `events.subscribe` | read | **admin** |

`host/src/role.rs` mints `Observer => ["read"]` and
`Controller => ["read","workspace","agent","terminal","orchestration"]`, and has a test
asserting `admin` is never included. Proven by direct call with minted tokens:

```
observer    session.snapshot   DENIED  forbidden: auth token scope denied
observer    events.subscribe   DENIED  forbidden: auth token scope denied
controller  session.snapshot   DENIED  forbidden: auth token scope denied
controller  events.subscribe   DENIED  forbidden: auth token scope denied
read+admin  session.snapshot   OK
read+admin  events.subscribe   OK
```

So the entire client (overview, live updates, resync machinery) could never have worked,
regardless of UI wiring. This is the root cause of the project never running end to end.

## F2. Luvus's real authorization rule is broader than the docs suggest

A token holding only `read` was able to call `agent.list`, `task.list`, `workspace.list`,
`pane.list`, and `terminal.backend.inventory`, even though `method_contracts` declares
those under the `agent` / `orchestration` / `workspace` / `terminal` scopes.

Effective rule, as observed: the `read` scope grants any method with `access: read` whose
declared scope is not `admin`. Domain scopes (`workspace`, `agent`, `terminal`,
`orchestration`) are what gate `access: write` methods in that domain. `admin`-declared
methods require `admin` explicitly and are not covered by `read`.

Consequence: an observer role is genuinely useful (it can read everything, including
`terminal.backend.observe`, which is `access: read`) and is correctly barred from
`terminal.backend.control` (`access: write`).

## F3. `admin` is delegatable

`uhp.token.create` accepted `["admin"]`, `["read","admin"]`, and `["all"]`, returning
`{type, id, token, scopes, expires_at}`. Minting requires admin authority, which the
bridge has because it connects as the local owner with no token
(`authorization.default = local_owner`).

## F4. Adopted resolution: split-token routing in the bridge

Two tokens, both memory-only, both minted per bridge invocation, neither ever crossing
the SSH boundary (the bridge injects `auth`; a client-supplied `auth` is refused):

- **Session token** — scopes `["read","admin"]`. Routed to `session.snapshot` and
  `events.subscribe` and nothing else. Both are `access: read`.
- **Action token** — the role's scopes. Routed to every other permitted method.

The device's real privilege is the bridge's per-frame method allow-list, not the token
scope, so least privilege is enforced at the bridge. This is only sound because the
bridge parses and authorizes *every* frame for the whole life of the channel; the
previous first-frame-only gating would have made the admin token reachable.

## F5. `method_contracts` is the authoritative allow-list, available at runtime

`uhp.capabilities` returns 178 entries of `{method, access, idempotent, scope}`, plus
`authorization.scopes` and `limits`. The bridge should derive its allow-list from this at
startup rather than hardcoding a table, and deny unknown methods by default.

Observed `limits`: `frame_bytes = 1048576` (matches the bridge's existing 1 MiB cap),
`connection_capacity = 80`, `active_connections = 1`, `event_queue = 256`,
`event_replay = 256`, `event_replay_bytes = 1048576`, `event_subscribers = 64`,
`terminal_stream_capacity = 8`, `terminal_stream_frame_bytes = 65536`.

`events`: `{loss: "resync_required", resume: "after_sequence"}`.
`concurrency`: `{mutation_guard: "if_revision"}`.
`agent_states`: `idle`, `working`, `blocked`, `done`.

## F6. Real result shapes that contradict the client

### `session.snapshot`
Nesting `workspaces[].tabs[].panes[]` is CORRECT. Carries `type: "session_snapshot"`,
`session`, `server_generation`, `event_sequence`, `protocol{name,major,minor}`.
Workspace adds `branch`, `cwd`, `index`, `name`, `pinned`, `active`.
Pane adds `agent`, `agent_authority`, `agent_session`, `agent_status`,
`content_revision`, `cwd`, `focused`, `kind`, `pane_id`, `terminal_id`,
`root_process{pid,start_marker}`.

**`session.snapshot` contains no tasks field.** Tasks come only from `task.list`.

### `agent.list`
`{agents: [...]}`, each: `pane` (NOT `pane_id`), `agent` (the kind, e.g. `pi`, `zsh`),
`name` (usually **null**), `status`, `authority`, `state_source`, `session`, `focused`,
`workspace`, `workspace_name`, `tab`, `cwd`, `branch`, `project`, `repo`, `worktree`.

The client's `AgentSummary(paneId, name, status)` is wrong twice: the field is `pane`, and
`name` is normally null while `agent` holds the label a user would recognise. Any UI
showing agent names renders blank today.

### `agent.explain`
`{type: "agent_explanation", pane, agent, status, available, authority, session, revision,
identity{confidence,source}, state_evidence{source,confidence,blocked_hint,rule_priority,rule_region}}`.

### `task.list`
`{type: "task_list", revision, tasks: []}`. The per-task object shape was not observable
(no tasks existed) and must come from source, not guesswork.

### `task.next`
Returns a **variant keyed by `type`**: `{type: "none", message: "no ready tasks", revision}`
when empty. Not a nullable task. Callers must switch on `type`.

### `terminal.backend.inventory`
`{server_generation, terminals: [...]}`, each: `terminal_id`, `pane_id`,
`content_revision`, `terminal_title`, `label`, `cwd`, `workspace{index,name,root}`,
`tab{index,name}`, `root_process{pid,start_marker}`.

Confirms the terminal locator is `server_generation` + `terminal_id` + `pane_id`.

### `terminal.backend.capture`
`{type: "terminal_backend_capture", text, lines, bytes, mode, ansi, truncated,
content_revision}`. It does **not** echo the locator, so a client-side `TerminalFrame`
must carry the identity from the request.

### `workspace.get`
`{type: "workspace", workspace: "1" (STRING), workspace_id, name, active, active_tab,
tabs (count), pinned, branch, ahead, behind, cwd, terminal_cwd, display_position, revision}`.
Note `workspace` is a string here while `session.snapshot` uses an integer `index`.

### `terminal.backend.snapshot`
Rejected `invalid_params` for `{server_generation, terminal_id, pane_id}`. Its real
parameters must be read from source.

## F7. Every state result carries `revision`, and the client ignores it

`concurrency.mutation_guard = if_revision`; mutations may pass `if_revision` and fail with
`revision_conflict`. The Luvia client neither reads `revision` nor sends `if_revision`, so
concurrent edits from a desktop Luvus and the phone can silently clobber each other. This
was missed by the earlier audit.

## F8. Framing

One LF-terminated JSON request and one response per connection for ordinary calls;
`events.subscribe`, `terminal.backend.observe`, and `terminal.backend.control` turn the
connection into a bounded stream after their acknowledgment. `events.subscribe`
acknowledges with `{loss_behavior: "resync_required_then_close", queue_capacity, replayed, ...}`.

The client's `open` prelude is **Luvia's own bridge protocol**, not UHP. UHP's only
handshake is `uhp.capabilities`.
