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
A client that already holds a fresh `session.snapshot` should subscribe with
that snapshot's `event_sequence` rather than snapshotting again; replay covers
the gap.

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

Read with ADR 0001. Luvia does not vendor the UHP schema tree; live
`uhp.capabilities.methods` is the method catalog.

### Authorization

- `session.snapshot` and `events.subscribe` are now `required_scope = "read"`
  (`src/api/capabilities.rs:276-289`). F1 is fixed at HEAD; the bridge skips
  the `read,admin` session token when those contracts are `scope=read`, and
  still mints it for older Hosts (ADR 0002).
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

## 7. Delta 0.13.4 → 0.14.2 (`d94ff20`, 107 commits after `c42b78c`) for the UHP-first surface

Read with ADR 0001. Protocol still `luvus-uhp` 1.0 (`src/api/mod.rs:12-14`). Luvia does not vendor the schema tree; live `uhp.capabilities.methods` is the method catalog. Ground: `/Users/misaka/Developer/OpenSource/luvus`.

### Authorization / capabilities

- Sock `required_scope` table is the same prefix machine (`src/api/capabilities.rs:296-338`): `session.snapshot` / `events.subscribe` stay `read`; `agent.*` → `agent`; `task.*`/`lease.*`/`automation.*` → `orchestration`; `workspace|pane|files|git|mission|diff|worktree|search` → `workspace`; `terminal.backend.*` → `terminal`; `module.*` → `extensions`; else `admin`. `task.next` is still in `READ_ONLY_METHODS` (`capabilities.rs:256`) and still claims.
- Sock `uhp.capabilities` did **not** gain `access{}`. `#302` projects `access.{mode,allowed_methods,limits}` plus optional `authorization.scopes += machine` only on `luvus uhp access` (`src/uhp/gateway.rs:469-518`). Irrelevant to the bridge sock path.
- New sock methods of note: `task.retry` (write, orchestration, also in `atomic_methods`, `capabilities.rs:114,431`). `machine.*` is **not** in sock `METHODS`; only Access (`src/machine/api.rs:8-20`, `capabilities.rs:347-348`).
- Additive `limits`: `task_title_bytes`, `task_prompt_bytes`, `task_attempts`, `agent_row_titles`, … (`capabilities.rs:408-418`). Luvia `mapCapabilities` ignores them.

### Control Access allow-list (`luvus uhp access`, not the phone path)

`src/uhp/gateway.rs:564-590` — Control now allows `agent.keys` (`#297`), `pane.rename` (`#326`), `automation.{create,update,enable,disable,rebind,delete,run}`, `task.retry`, plus the old five (`workspace.focus`, `tab.focus`, `pane.focus`, `agent.prompt`, `terminal.backend.control`). Still no `diff.note.*` / `task.add`. Decision in §6 stands.

### Agent surface (scope `agent`)

| method | params delta | result delta | notes |
|---|---|---|---|
| `agent.list` | none | additive `workspace_id`, `terminal_id` | `#264` `agents.rs:55-71`. Luvia already maps `workspaceId`. |
| `agent.get` | none | none | |
| `agent.read` | none | additive `content_revision`, `terminal_id` | `agents.rs:319-320`. Luvia already maps both. |
| `agent.prompt` | `until` is **array** of 1..4 states (`params.rs:576-593`); string → `invalid_request`. Luvia already sends a 1-array. | additive `observed_state` when evidence ≠ `queued` (`agent_workflow.rs:131-134`) | `#314`: `wait=true` requires a new `working`/`blocked` transition before matching `until`. New error `agent_not_ready` (`agent_workflow.rs:137-141`) — Luvia has no dedicated Failure. Default `wait=false` unchanged. |
| `agent.keys` | optional pair `if_content_revision` + `terminal_id` (both or neither) (`agents.rs:193,226-252`) | still `{type:ok, pane}` | `#328`. Mismatch → `content_revision_conflict`. Extra keys rejected (`reject_api_fields`). Luvia already sends the fence. |
| `agent.wait` | `#253`: `status` **or** `statuses` (1..4) (`request.schema.json:99-113`) | `{type:agent_wait, matched, pane, status}` | Luvia does not call it. |
| `agent.sessions` | none | none | `agents.rs:603-608` still `{agent, session_id, cwd}`. |
| `agent.resume` / `agent.fork` / `agent.name` | none | none | `agent.name` still silent (no event / no sequence bump). Catalog `$comment` at `event-catalog.schema.json:2`. |

### Orchestration

Task **status enum unchanged**: `queued claimed running blocked review done merging merged failed` (`orch/mod.rs:81-92`, catalog `event-catalog.schema.json` task.status). Luvia `parseTaskStatus` / `parseAgentStatus` already `else → Unknown` (`Mapping.kt:728-747`) — **no exhaustive-map break**.

`task_json` additive fields (`projection.rs:57-91`): `prompt` (manual only; omitted for automation), `project{workspace_id,root}`, `attempt`, `previous_attempts[]`. Luvia `mapTask` ignores them (`Mapping.kt:1070-1098`).

| method | params delta | result delta | notes |
|---|---|---|---|
| `task.list` | none (no project filter) | tasks[] additive fields above | Still global ledger dump (`orchestration.rs:394-399`). |
| `task.get` | none | same object | |
| `task.add` | optional `prompt` (LF ok, 32 KiB, `#304`); optional `workspace_id`/`pane`; **now** `reject_api_fields` (`orchestration.rs:362-376`) | task object additive | **BREAKING** on multi-project Hosts: omitted workspace → `workspace_required` (`orchestration.rs:1173-1177`). Luvia `addTask` sends only title/paths/deps/gate (`Client.kt:445-465`). Single-project still implicit. |
| `task.start` | optional `focus` bool default true (`#360`, `orchestration.rs:432-438`) | unchanged start shape | Resolves project via `resolve_task_workspace` (`board.rs:393`). Can `workspace_mismatch` / `workspace_unavailable` / `workspace_required` for projectless legacy tasks. |
| `task.claim` | none | additive task fields | Binds pane’s project (`orchestration.rs:461-463`); `workspace_mismatch` possible. |
| `task.next` | now needs a project (workspace_id/pane or unambiguous session) | `none` vs `task` unchanged | **BREAKING if called** without workspace on multi-project (`orchestration.rs:572-596`). Phone must still never call it. |
| `task.done` / `task.heartbeat` / `task.delete` | none | delete still `{type:task, task}` (`orchestration.rs:671-676`) | |
| `task.retry` **new** | `{id}` | `{type:task, task}` **or** `{type:automation_run, run}` (`orchestration.rs:539-546`) | `#348`. Only `done|failed|review|blocked`; else `not_retryable` (`orch/mod.rs:656-662`). Emits `task.retried {id, attempt≥2}`. Gate on `methods`. |

`#361` scopes **ownership of mutations/leases**, not `task.list`.

### `session.snapshot`

Still `{type, protocol, session, server_generation, event_sequence, workspaces[]}` with tabs/panes; **no tasks, no `workspace_id`/`tab_id`** (`core.rs:152-239`). Additive pane field `agent_name` (`#300`, `core.rs:186`) — operator alias. Luvia `mapSnapshot` reads `name`, not `agent_name` (`Mapping.kt:322`), so snapshot-derived `AgentSummary.name` stays null; `agent.list` already carries `name`.

### Streams

`events.subscribe` ack unchanged (`src/ipc/api.rs:2094-2099`): `{type:subscription_started, sequence, replayed, queue_capacity, loss_behavior:resync_required_then_close}`.

`terminal.backend.control`/`observe` ack unchanged (`src/ipc/api.rs:1466-1477`). `#299` only fixes observe cursor vs emitted frame.

### Events (catalog grew past the 0.13.4 “50”)

Phone-relevant **new**:
- `task.retried` `{id, attempt}` (`event-catalog.schema.json` `$defs.task_retried`). Luvia `parseBusEvent` → `Ignored` (not in the task name list, `Mapping.kt:788-792`).
- `automation.*` run/definition events — `Ignored` today; only if the phone surfaces automations.
- `pane.renamed` already mapped.

`agent.name` still does **not** emit.

### Machines (`#333`)

`machine.list/get/status/sessions` (read) and `machine.add/rename/enable/disable/remove` (write, required `if_revision`). Access-only. Not a Device-contract change.

### BREAKING for Luvia (sock)

1. **`task.add` (and `task.start`/`task.claim`/`task.next`) project scope** — multi-project Host without `workspace_id`/`pane` → `workspace_required` / `workspace_mismatch`. Luvia does not send `workspace_id`.
2. **`task.add` unknown keys** now `invalid_request` (Luvia’s current params are fine).
3. **`agent.prompt` `wait=true`** — will not complete on a pre-existing idle/done; needs a new working/blocked observation (`#314`). Default `wait=false` OK.
4. Status enums **not** extended; `Unknown` unused for this delta.
5. No fields removed/renamed on methods Luvia already parses, except snapshot alias lives on `agent_name` (additive miss, not a parse break).

### Verdict per Luvia method

| method | verdict |
|---|---|
| `agent.list` | additive |
| `agent.get` | unchanged |
| `agent.read` | additive |
| `agent.prompt` | additive (behavioral if `wait=true`) |
| `agent.keys` | additive |
| `agent.sessions` | unchanged |
| `agent.resume` | unchanged |
| `agent.fork` | unchanged |
| `agent.name` | unchanged |
| `diff.*` / `diff.note.*` | unchanged |
| `git.status` / `git.log` | unchanged |
| `task.list` | additive |
| `task.get` | additive |
| `task.add` | **breaking** (multi-project) |
| `task.done` | unchanged |
| `task.claim` | additive (can error `workspace_mismatch`) |
| `task.delete` | unchanged |
| `task.start` | additive (project errors) |
| `task.next` | **breaking** if called; still forbidden |
| `task.heartbeat` | unchanged |
| `mission.snapshot` | unchanged |
| `terminal.backend.*` | unchanged (ack/params) |
| `files.*` | unchanged (`files.open` still TUI `{type:ok}`, `content.rs:567-581`) |
| `search.*` | unchanged |
| `worktree.*` | additive (`#234` lists every checkout) |
| `automation.*` | additive (`#319` access on task contract) |
| `workspace.*` | unchanged |
| `pane.*` | additive (`pane.rename` on Control Access only) |
| `session.snapshot` | additive (`agent_name`) |
| `events.subscribe` | unchanged |

### Luvia response to this delta

- `LuviaSession.addTask` gained `workspaceId`; `TaskBoard.add` sends the focused workspace's id (from `agent.list` `workspace_id`, else Mission rows) so multi-project Hosts stop answering `workspace_required`. Null on single-project Hosts keeps the implicit project.
- `task.next` remains forbidden (ADR 0001); the project-scoping change does not affect the phone.
- `agent.prompt` stays `wait=false`; `agent_not_ready` surfaces as a generic server error.
- **Implemented:** `task.retry` (`LuviaSession.retryTask` / `HostUhp.retryTask`), `task.retried` → `BusEvent.TaskPayload`, `session.snapshot` `pane.agent_name` → `PaneSummary.agentName`, `automation.*` events → `BusEvent.AutomationChanged` with live AutomationBoard refresh. `machine.*` is still not surfaced.

