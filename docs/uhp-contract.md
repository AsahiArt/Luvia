# UHP wire contract — source gaps

Ground truth: Luvus source under `/Users/misaka/Developer/luvus`.
Do **not** duplicate live-probe facts. Read `uhp-empirical-findings.md` first
(F1–F8, probed on 0.13.2: admin on `session.snapshot`/`events.subscribe` — fixed
in 0.13.4, see §5; `read` covering non-admin reads, result shapes already
probed, locator triple, `after_sequence`, `revision`/`if_revision`, 1 MiB frames).
Citations are `path:line` into `/Users/misaka/Developer/luvus`.

---

## 1. Task objects and task.* methods Luvia calls

### Per-task object (`task.list` `tasks[]`, and `task` on add/get/start/next/done)

`task.list` returns `{"type":"task_list","tasks": <serde of Vec<Task>>}`
(`src/app/dispatch.rs:3817-3820`). `handle_api` then inserts `revision`
(`src/app/dispatch.rs:1144-1177`) except on `uhp.capabilities` / `session.snapshot`.

`fn task_json` is `serde_json::to_value(t)` (`src/app/dispatch.rs:5542-5545`).
There is **no** `rename_all` on `Task`; `TaskStatus` is `rename_all = "lowercase"`.

`src/orch/mod.rs:57-84` — exact fields:

| JSON field | type | notes |
|---|---|---|
| `id` | string | `t1`, `t2`, … (`src/orch/mod.rs:167`) |
| `title` | string | |
| `status` | string enum | `queued` `claimed` `running` `blocked` `review` `done` `failed` (`src/orch/mod.rs:20-54`) |
| `assignee` | number \| null | pane id `PaneId.0` once claimed |
| `deps` | string[] | task ids |
| `paths` | string[] | intended file globs |
| `gate` | string \| null | quality-gate command |
| `outputs` | string[] | capped 100 × 4 KiB (`MAX_TASK_LOG`/`MAX_LOG_ENTRY`) |
| `notes` | string[] | same cap |
| `worktree` | string \| null | worker path once started |
| `branch` | string \| null | |
| `context` | number \| null | 0..1 last heartbeat |
| `created` | integer | unix seconds |
| `updated` | integer | unix seconds |

`session.snapshot` does **not** include tasks (EMPIRICAL F6; builder
`src/app/dispatch.rs:4011-4076` only emits `workspaces`/`tabs`/`panes`).

### `task.add` — mutation

Params (`src/app/dispatch.rs:3803-3815`):
- `title` **required** non-empty string (`req_str` → `invalid_request` "title is required", `src/app/dispatch.rs:5512-5517`).
- `paths` optional string array (missing/wrong type → `[]`, `str_array` 5525+).
- `deps` optional string array (same). Unknown dep → `unknown_dep` (`src/orch/mod.rs:159-162`).
- `gate` optional string (`opt_str`).
- Empty title → `bad_request` (`src/orch/mod.rs:150-152`). Ledger full → `task_limit` (153-157).

Does **not** call `reject_api_fields`; extra keys ignored.

Success: `{"type":"task","task":<Task>}` plus `revision`. Emits `task.added` with the same task object.

### `task.get` — read

Params: `id` required string (`src/app/dispatch.rs:3821-3826`).
Missing → `not_found` `"no such task: {id}"`. Extra keys ignored.
Success: `{"type":"task","task":<Task>}` plus `revision`.

### `task.start` — mutation

Params (`src/app/dispatch.rs:3836-3847`, `src/app/board.rs:43-171`):
- `id` required.
- `branch` optional string.
- `agent` optional string (launch line sent into the new worker pane).

Errors: `not_found`, `already_claimed`, `deps_unmet`, `not_a_repo`, `spawn_failed`, `git_error`.

Success: `{"type":"task","task":<Task>,"pane":"<pane id string>","worktree":"<path>"}` plus `revision`.
Emits `task.started` `{id, pane, worktree, branch}` (`src/app/board.rs:160-168`).
Side effect: claims, sets status `running`, binds worktree, optional path lease.

### `task.heartbeat` — mutation

Params (`src/app/dispatch.rs:3906-3921`):
- `id` required.
- `context` **required** JSON number (`as_f64`). Missing → `invalid_request` `"context (0..1) is required"`.
  Clamped to 0..1 (`src/orch/mod.rs:213-221`). Threshold `0.85` (`COMPACTION_THRESHOLD`).

Success: `{"type":"ok","over_threshold": <bool>}` plus `revision`.
If over threshold, also emits `task.needs_compaction` `{id, context}`.

Luvia currently sends only `{id}` — that call **fails**.

### `task.done` — mutation

Params: `id` required (`src/app/dispatch.rs:3869-3876`).
`complete_task` (`src/app/board.rs:348-391`):
- `context > 0.85` → `needs_compaction` (does not complete).
- no gate → finalize Done immediately, `gate_running: false`.
- gate set → status Running, emit `task.gate_running` `{id, gate}`, `gate_running: true`.

Success: `{"type":"task","task":<Task>,"gate_running":<bool>}` plus `revision`.
On immediate done, emits `task.done` (full task JSON) and `task.ready` `{id}` for dependents.

### `task.next` — declared read-only, but claims

`is_read_only` includes `task.next` (`src/api/capabilities.rs:229-231`) so `if_revision` on it is `invalid_request`.
Implementation **mutates** (`src/app/dispatch.rs:3882-3904`):

- No ready queued task → `{"type":"none","message":"no ready tasks"}` plus `revision` (probed).
- Else if `start: true` → same success shape as `task.start` (optional `agent`).
- Else **claims** for `pane` or the focused pane (`orch_pane` / `resolve_pane`, `src/app/dispatch.rs:3973-3978`, `3987-3997`). Empty params uses the focused pane. No pane at all → `no_pane`.
  Success: `{"type":"task","task":<claimed Task>}` plus `revision`. Emits `task.claimed`.

Callers **must** switch on `result.type` (`none` vs `task`).

---

## 2. `terminal.backend.snapshot`

**Params: empty object.** `maxProperties: 0` (`protocol/uhp/v1/terminal/schema/methods/snapshot.schema.json:1-7`).
Live: `backend::reject_unknown_fields(params, &[])` (`src/app/backend.rs:210-218`).
Sending `{server_generation, terminal_id, pane_id}` is `invalid_params` `unknown parameter: …` — matches the probe.

**Not streaming. Not a mutation** (`READ_ONLY_METHODS`, `src/api/capabilities.rs:247`).

Result (`src/app/backend.rs:210-218`):

```json
{
  "type": "terminal_backend_snapshot",
  "server_generation": "<32 hex>",
  "event_sequence": <u64>,
  "terminals": [ /* same entries as terminal.backend.inventory */ ],
  "truncated": <bool>
}
```

Inventory entry shape is EMPIRICAL F6. Snapshot adds the event-sequence fence;
it does **not** take a locator.

---

## 3. `terminal.backend.control` — bidirectional channel

### Open (ordinary UHP request)

Params (`protocol/uhp/v1/terminal/schema/methods/control.schema.json`):
required `server_generation` (32 hex), `terminal_id` (32 hex), `pane_id` (`^[1-9][0-9]{0,9}$`);
optional `expected_root` `{pid, start_marker?}`, `mode` `visible`|`recent_unwrapped` (default visible),
`lines` 1..200 (default 80), `ansi` bool (default true). Extra keys → `invalid_params`.

Handled in the socket worker, **not** `handle_api` (`src/ipc/api.rs:1754-1767`).

Ack (single JSON response, then the connection stays open) `src/ipc/api.rs:1385-1399`:

```json
{
  "id": "<request id>",
  "result": {
    "type": "terminal_backend_stream",
    "mode": "control",
    "server_generation": "…",
    "terminal_id": "…",
    "pane_id": "…",
    "sequence": <u64 fence>,
    "content_revision": <u64>,
    "ansi": <bool>,
    "capture_mode": "visible" | "recent_unwrapped",
    "lines": <1..200>,
    "frame_bytes": 65536,
    "queue_capacity": 2,
    "loss_behavior": "resync_required_then_close"
  }
}
```

Observe uses the same result with `"mode":"observe"`.

Exclusive lease: second control stream on the same `terminal_id` → `control_conflict`
(`src/ipc/api.rs:194-214`, `1356-1360`). Combined observe+control cap 8 → `limit_exceeded`.

### Subsequent server frames (no `id`)

Immediately after ack, one `terminal.frame` is written (`src/ipc/api.rs:1418-1424`).
Later frames only when that terminal's `terminal.output_ready` advances `content_revision`
(`src/ipc/api.rs:1441-1455`). `terminal.exited` / `terminal.closed` are forwarded then the stream ends.

`terminal.frame` shape (`src/ipc/api.rs:1173-1188`, schema `protocol/uhp/v1/terminal/schema/event.schema.json`):

```json
{
  "event": "terminal.frame",
  "sequence": <u64>,
  "data": {
    "server_generation": "<32 hex>",
    "terminal_id": "<32 hex>",
    "pane_id": "<decimal>",
    "content_revision": <u64>,
    "mode": "visible" | "recent_unwrapped",
    "ansi": <bool>,
    "text": "<string, ≤65536 bytes>",
    "lines": <1..200>,
    "bytes": <0..65536>,
    "truncated": <bool>
  }
}
```

Overflow: `terminal.resync_required` then close (see §4).

### Client → server action frames (after ack only)

Normative schema `protocol/uhp/v1/terminal/schema/control-frame.schema.json`.
Live parser `src/ipc/api.rs:1214-1290`.

```json
{"id":"<1..128 [A-Za-z0-9._:-]>","action":"type_literal|submit_text|send_key","params":{...}}
```

- `additionalProperties: false` on the envelope. Unknown `action` → `invalid_params`.
- `type_literal` / `submit_text`: `params` `{ "text": string, minLength 1, maxLength 262144 }` only.
- `send_key`: `params` `{ "key": <enum in §5> }` only.
- Locator is **injected by the server** from the leased terminal; client must not send it on action frames (`src/ipc/api.rs:1263-1276`). Extra param keys → `invalid_params`.

Each action is dispatched as the corresponding unary method and answered with an ordinary
`{"id":…,"result":{...}}` or `{"id":…,"error":{...}}` on the **same** stream
(`src/ipc/api.rs:1277-1289`). Input success (`type_literal`/`submit_text`/`send_key`):

```json
{"type":"terminal_backend_action","state":"succeeded","dispatch":"queued"}
```

(`src/app/backend.rs:1279-1281`). Timeout waiting for the app loop: `timeout`.

Action replies and `terminal.frame` events share one writer mutex; interleaving is possible.

### Clean close

There is **no** close action. The worker loops on `reader.read` until `Ok(0)` / error
(`src/ipc/api.rs:1473+`). Dropping the socket (or shutting down the write side so the
server sees EOF) unsubscribes, drops `TerminalControlLease` and `TerminalStreamPermit`.
Treat EOF as possible loss even if `terminal.resync_required` was not seen (docs + `loss_behavior`).

`uhp proxy` is one-shot and must not be used for this stream (EMPIRICAL F8 / docs).

---

## 4. `events.subscribe`

### Params

Schema `protocol/uhp/v1/schema/request.schema.json` `$defs.subscribeParams`:
`additionalProperties: false`, optional `after_sequence` integer `minimum: 0`.

Live (`src/ipc/api.rs:1934-1971`): object whose **only** allowed key is `after_sequence`.
Any other key → `invalid_params` `"runtime event subscription accepts only after_sequence"`.
`after_sequence` if present must be `as_u64()` (non-negative integer).
**No event-name filter, no `where`.** `EventFilter::All` (`src/ipc/api.rs:1961-1966`).
Omitted `after_sequence` → live-only after the ack fence (no replay).

Replay: events with `sequence > after_sequence` still in the 256-frame / 1 MiB window
(`src/ipc/api.rs:1086-1100`, `71-72`).

Cursor errors are on the **ack**, not an event:
- `after_sequence > current` → `invalid_params` + `sequence` (current fence) (`1984-1988`).
- `after_sequence < replay_floor` → `resync_required` `"requested event history is no longer retained"` + `sequence` (`1993-1997`).

Ack (`2006-2012`):

```json
{
  "id": "…",
  "result": {
    "type": "subscription_started",
    "sequence": <fence>,
    "replayed": <usize>,
    "queue_capacity": 256,
    "loss_behavior": "resync_required_then_close"
  }
}
```

Then replayed lines, then live lines. Streaming. Not a mutation.

### Overflow / gap event

Slow subscriber (`try_send` Full) → `active=false`, store overflow sequence
(`src/ipc/api.rs:1009-1026`). Forwarder then writes (`1132-1140`, `2027-2038`):

```json
{
  "event": "events.resync_required",
  "sequence": <overflow sequence>,
  "data": { "reason": "subscriber_overflow" }
}
```

then closes. `terminal.backend.events.subscribe` / observe / control use
`"event":"terminal.resync_required"` with the same `data.reason`.

Clients must also treat EOF as loss (final control frame is best-effort).

Envelope for every bus event (`src/ipc/api.rs:1009-1011`, `protocol/uhp/v1/schema/event.schema.json`):

```json
{ "event": "<name>", "sequence": <u64>, "data": { ... } }
```

---

## 5. Delta v0.13.2 → 0.13.4 (`c42b78c`) for the UHP-first surface

Read with ADR 0001. Vendored `protocol/uhp/v1` is synced to this commit
(`protocol/uhp/UPSTREAM_COMMIT`).

### Authorization

- `session.snapshot` and `events.subscribe` are now `required_scope = "read"`
  (`src/api/capabilities.rs:276-289`). F1 is fixed at HEAD; the bridge's
  `read,admin` session token stays for older Hosts (ADR 0002).
- Effective rule (`src/ipc/api.rs:309-314`): a token authorizes a method if it
  holds `all`, the exact scope, or `read` while the method is read-only and not
  `admin`. `read` therefore covers `task.next` — which claims. Never call it.
- The `allowed_method` allow-list in `src/uhp/gateway.rs:403-415` belongs to
  `luvus uhp access` and does not apply to `luvus.sock` clients.
- `host.*`, `session.list/status`, `skill.*`, `integration.*` are served only
  by `luvus uhp proxy` (`src/api/host.rs:48-52,76-81`) and reject session
  `auth`. Unreachable through the bridge.

### Agent surface (scope `agent`, `capabilities.rs:293-294`)

| method | params | result | notes |
|---|---|---|---|
| `agent.read` | `target`, `lines?`=200, `source?` `visible`\|`recent` | `{type:agent_read, pane, text}` | `dispatch.rs:2584-2604`. Transcript without a stream. |
| `agent.prompt` | `target`, `text` 1..262144, `wait?`=false, `until?`, `timeout_s?`=300 | `{type:agent_prompt, pane, submitted, matched, status, baseline_revision, content_revision, evidence}` | `dispatch.rs:4736-4803`. One atomic submit (`pty.rs:858-876`). Busy → `agent_prompt_busy`. Timeout still `submitted:true`. |
| `agent.keys` | `target`, `keys[]` non-empty | `{type:ok, pane}` | `dispatch.rs:2553-2582`; names `5591-5630`: `enter esc tab space backspace delete up down left right home end pageup pagedown ctrl+<a-z>` or one printable. Unknown key fails the batch. |
| `agent.send` | `target`, `text` | `{type:agent_send, …}` | Paste + separate Enter after 45 ms (`2543-2545`). Not atomic. Phone uses `agent.prompt` instead. |
| `agent.sessions` | `{}` | `{type:session_list, sessions:[{agent, session_id, cwd}]}` | `dispatch.rs:2834-2846`. |
| `agent.list` | `{}` | `{type:agent_list, agents[]}` | `type` field new vs F6 (`2462`). |

### Review surface (scope `workspace`, `capabilities.rs:299-311`)

Works without an open DIFF pane; operates on the git snapshot and persisted
notes (`dispatch.rs:3392-3810`).

| method | params | result |
|---|---|---|
| `diff.refresh` | `{}` | `{type:ok, refresh:"complete", generation}` |
| `diff.list` | `layer?` `staged\|worktree\|untracked\|conflict` | `{type:diff_list, repo, branch, generation, fingerprint, omitted, refreshing, files[]}` |
| `diff.get` | `path`, `layer?`, `include_patch?` | `{type:diff, file, additions, deletions, binary, truncated, omitted_lines, hunks[]}` |
| `diff.note.list` | `state?` `open\|resolved\|outdated\|orphaned`, `file?` | `{type:diff_notes, notes[]}` |
| `diff.note.add` | `file`, exactly one of `old_line`\|`new_line`, `end_line?`, `body`, `kind?` `issue\|question\|suggestion\|praise`, `layer?` | `{type:diff_note, note}` author `external` |
| `diff.note.edit/resolve/reopen` | `id` (+ `body`) | `{type:diff_note, note}` |
| `diff.note.remove` | `id` | `{type:ok, removed}` |
| `diff.note.send` | `to`, `ids[]` or `all_open:true` | `{type:diff_note_send, pane, target, count}`; paste + delayed Enter into the agent pane (`src/diff.rs:1520-1571`). Never retry. |
| `git.status` | `workspace?` | `{type:git_status, branch, upstream, ahead, behind, staged, unstaged, untracked, stashes}` |
| `git.log` | `n?`=30 | `{type:git_log, commits:[{sha, subject, author, when, refs}]}` |

Note object (`dispatch.rs:5713-5733`): `id, review, author, kind, body, state,
path, layer, side, start_line, end_line, revision, deliveries[], created_at_ms,
updated_at_ms`.

TUI-only, do not call: `diff.open`, `diff.navigate`, `git.open`, `files.open`.

### Orchestration changes

- `task.start` / `task.next` accept `mode` `worktree\|workspace` and
  `workspace_id` (`request.schema.json:119-144`); start result adds `mode,
  workspace_id, tab_id, cwd`.
- Task statuses add `merging`, `merged`; task object adds optional `mode`,
  `workspace_worker{workspace_id, tab_id, root}` (`src/orch/mod.rs:48-66,99-138`).
- `task.merge` → `merge_unavailable` for workspace-mode workers
  (`src/orch/board.rs:561-566`).
- `mission.snapshot` (`scope?` `workspace\|all`) → `{type:mission_snapshot,
  summary{agents, tokens, cost_usd, burn_usd_per_hour}, rows[{kind live\|resumable,
  pane?, agent, state, workspace, workspace_id, workspace_name, tab?, location,
  usage?}]}` (`dispatch.rs:3851-3887`, `src/mission.rs:59-107`). Scope
  `workspace`. Not in 0.13.2 — gate on `uhp.capabilities.methods`.

### Events

Catalog of 50 general events: `protocol/uhp/v1/schema/event-catalog.schema.json`.
Wire envelope unchanged. Phone-relevant additions beyond F8:
`agent.hook {pane, agent, kind, message, tool}`, `task.started {id, pane, mode,
workspace_id, tab_id, cwd, worktree, branch}`, `task.gate_running/gate_failed/
gate_passed`, `task.merge_started/merged/merge_conflict/merge_failed`,
`task.needs_compaction {id, context}`, `lease.acquired/released`.

### `session.snapshot`

Unchanged JSON vs F6 (`dispatch.rs:4270-4356`). Still no tasks, no
`workspace_id`/`tab_id` (use `workspace.list` / mission rows).

## 6. "Remote" in 0.13.4 and why the bridge stays

Three unrelated features share the word. None replaces `luvia-host`.

| Feature | What it is | Phone use |
|---|---|---|
| `luvus --remote <host>` | TUI attach over plain ssh (`src/cli.rs:57,310`) | None; human-only |
| `luvus uhp proxy` | One-shot stdin/stdout UHP proxy (`ssh host luvus uhp proxy`). Only route to `host.*`, `session.list/status` (`src/api/host.rs:48-52`) | Optional side channel for Host-profile reads; no events, one process per request |
| `luvus uhp access [--control]` | Loopback TCP gateway + one-use pairing code. Transport-neutral: expects a provider (ssh, Tailscale) to forward `127.0.0.1:<port>` (`protocol/uhp/v1/access/README.md`) | Rejected, see below |

`uhp access` limits (`src/uhp/gateway.rs:403-415`, `website/.../remote-access.mdx:56-70`):

- `--control` allows exactly `workspace.focus`, `tab.focus`, `pane.focus`,
  `agent.prompt`, `terminal.backend.control`. No `agent.keys`, `diff.note.*`,
  `task.add/update/complete`. ADR 0001's surface is unreachable.
- Read gate is `is_read_only(method)`, so `task.next` passes and still claims.
- Pairing code is one-use, ≤5 min; authority is 24 h or process-bound. It
  models "lend this session to a client", not a durable Grant.
- The gateway itself is loopback-only; a provider is still required. `luvia-host`
  already is that provider, and additionally pins the Host key, keeps Grants,
  scopes by Role instead of a fixed five-method list, and works on 0.13.2.

Decision: keep `luvia-host bridge` → `luvus.sock` as the sole phone path.
If Host-profile methods are ever needed, the bridge may front `luvus uhp proxy`
for that namespace only; that is additive and does not change the Device contract.