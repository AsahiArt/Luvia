# Herdr backend contract

`luvia-host bridge` can front a running [Herdr](https://herdr.dev) server in
addition to Luvus. The phone never learns Herdr's socket protocol: the bridge
shells out to the installed `herdr` CLI (the authority for its own shapes) and
answers a **subset of UHP** with Luvus-compatible payloads. Everything the
phone already knows how to render (agents, panes, workspaces, snapshot, live
status events) works unchanged; everything Herdr has no equivalent for is
simply absent from `uhp.capabilities` and the phone hides it.

Empirically verified against `herdr 0.9.0` (protocol 22).

## 1. Discovery

`luvia-host discover` and the bridge prelude list Herdr sessions next to Luvus
sessions. Session entries gain an optional `backend` key; absence means
`luvus`.

```json
{"name":"default","default":true,"running":true,"backend":"luvus"}
{"name":"herdr","default":false,"running":true,"backend":"herdr"}
{"name":"herdr-<name>","default":false,"running":true,"backend":"herdr"}
```

- Herdr is probed with `herdr session list --json`; a session is `running`
  when the reported `running` flag is true. Missing `herdr` binary → no
  entries, no error.
- The Herdr default session is advertised as `herdr`; other sessions as
  `herdr-<name>`. Names must satisfy `validate_session_name`.
- A Herdr session is never `default: true`. The phone keeps preferring the
  Luvus default; when no Luvus session is running it falls back to the first
  running session, so a Herdr-only machine pairs with zero extra taps.

## 2. Open

`open` with a `herdr*` session skips Luvus token minting. The bridge answers
the prelude ack exactly as for Luvus (same `version`, `session`, `server`
metadata) with `server.backend = "herdr"`.

## 3. Method surface

| UHP method | Herdr CLI | Notes |
| --- | --- | --- |
| `uhp.capabilities` | synthesized | protocol `luvus-uhp 1.0` so existing client gating works; `server.name = "herdr"`, `server.version` from `herdr status --json`. Methods listed are exactly the rows below. |
| `session.snapshot` | `workspace list` + `pane list` + `agent list` | Build the Luvus snapshot shape: `workspaces[].tabs[].panes[]` with `id`, `title`, `agent_status`, `agent_name`, `cwd`, `terminal_id`, `focused`. Herdr pane ids (`w1:p1`) are passed through as strings. Tabs come from `pane.tab_id` grouping; tab label = `tab_id` when Herdr exposes none. |
| `workspace.list` | `workspace list` | |
| `workspace.focus` | `workspace focus` | Operator only |
| `pane.list` | `pane list [--workspace]` | |
| `pane.focus` | `pane focus`-equivalent via `agent focus`/`pane zoom` is **not** available; omit `pane.focus`. |
| `pane.rename` | `pane rename` | Operator only |
| `agent.list` | `agent list` | |
| `agent.get` | `agent get <pane_id>` | |
| `agent.read` | `agent read <pane_id> --source recent --lines N --format text` | `source: visible|recent` map 1:1; `ansi` → `--format ansi` |
| `agent.prompt` | `agent prompt <pane_id> <text>` | Operator only; no `--wait` |
| `agent.keys` | `agent send-keys <pane_id> <key...>` | Operator only. Translate UHP key names (`enter`, `esc`, `ctrl-c`, `up`, …) to Herdr names; reject unknown with `invalid_params`. |
| `events.subscribe` | poll | Stream. Poll `agent list` + `pane list` every 1500 ms; emit `pane.agent_status_changed` when a pane's `agent_status`/agent label changes and `pane.created`/`pane.closed`/`pane.focused` on topology diffs. `sequence` is a monotonic counter per stream. Snapshot first, then diffs. |

Everything else (tasks, review, automations, layout, terminal.backend.*,
luvia.acp.*) is **not** advertised. ACP sessions are still available on a
Herdr host because they do not depend on the backend.

Role gating is the same as Luvus: Observer grants get read-only methods only;
a write method from an Observer returns `forbidden`.

## 4. Status mapping

Herdr `agent_status` → UHP `agent_status`: `idle`→`idle`, `working`→`working`,
`blocked`→`blocked`, anything else → `unknown`. `agent_name` is Herdr's agent
label (or `kind` when unnamed).

## 5. Errors

CLI non-zero exit → UHP error `backend_error` with the trimmed stderr as
message. Missing binary at open time → prelude error `backend_unavailable`.
JSON that fails to parse → `backend_error` with `"herdr returned non-JSON"`.

## 6. Phone

- `DiscoveredSession.backend: String` (default `"luvus"`).
- `HostSnapshot.backend` surfaces through `HostUhpState` so the host row can
  badge `Herdr`.
- No other phone change is required: capability gating hides missing sections.
