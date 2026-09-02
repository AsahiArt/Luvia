# ADR 0003: Mutations are never auto-retried; a lost result becomes Unconfirmed

Status: accepted (2026-09-02)

## Context

Every UHP mutation the phone issues has side effects the Host cannot undo:

- `agent.prompt` performs one atomic PTY submit (`src/pty.rs:858-876`); a
  timeout still returns `submitted:true, matched:false, evidence:"timeout"`.
- `agent.keys` writes raw key bytes; a duplicate Enter can approve twice.
- `diff.note.send` pastes a hand-off message plus a delayed Enter.
- `task.*` mutations move ledger state; `if_revision` prevents lost updates
  but not double submission when the response is lost.

`is_idempotent` in Luvus (`src/api/capabilities.rs:329-335`) is false for all
of them. The SSH bridge can drop a response after the Host has acted.

## Decision

1. The shared engine never retries a request flagged `mutation = true`. Only
   read-only unary calls may be retried after reconnect.
2. When a mutation's response is lost (transport failure, bridge exit, or
   `agent.prompt` `evidence:"timeout"`), the UI enters **Unconfirmed** for that
   action. Unconfirmed is resolved by observation, never by resend:
   - Agent prompt / keys: re-read `agent.get` status and the Transcript
     (`agent.read`), compare `content_revision` to `baseline_revision`.
   - Review note send: re-list notes and inspect `deliveries[]`.
   - Task mutation: re-fetch the Task; compare `revision`.
3. Task mutations always pass the last seen `revision` as `if_revision` and
   surface `revision_conflict` as a refresh prompt, not an error.
4. `agent.prompt` is sent with `wait=false` from the phone; the UI waits on
   `pane.agent_status_changed` instead of holding the request open across SSH.

## Consequences

- Every mutating UI action has a confirmation step and an Unconfirmed
  rendering; there is no generic "retry" button on mutations.
- Read paths may be aggressively refreshed; write paths are deliberately slow.
- Terminal input (`type_literal`, `submit_text`, `send_key`) follows the same
  rule when terminal control ships.
