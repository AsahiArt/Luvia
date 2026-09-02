# Luvia

Mobile client for a Luvus host. The phone reaches Luvus only through `luvia-host` over pinned SSH; it never holds a Luvus credential.

## Language

### Pairing and trust

**Host**:
A machine the user owns that runs Luvus and `luvia-host`. The unit the phone pairs with, connects to, and lists.
_Avoid_: Server, machine, remote

**Device**:
One phone identity, represented by its SSH public key. A Host grants a Device a Role.
_Avoid_: Client, phone, app instance

**Grant**:
A Host's durable record that a Device may connect, with its Role. Created by `luvia-host pair`, removed by `revoke`.
_Avoid_: Pairing record, registration

**Pairing code**:
The `luvia1:` payload a Host emits once per Grant. It pins the Host's SSH key fingerprints and names the Device key it belongs to.
_Avoid_: QR, token, invite

**Role**:
What a Grant lets a Device do: Observer (read) or Controller (read plus workspace, agent, terminal, orchestration).
_Avoid_: Permission level, mode

### Authority

**Session token**:
Credential minted by the Host bridge with `read,admin`, spent only on `session.snapshot` and `events.subscribe`. Never leaves the Host. Kept for Hosts older than 0.13.4; on newer Hosts those two methods accept `read` alone.

**Action token**:
Credential minted by the Host bridge carrying exactly the Device's Role scopes. Spent on every other UHP call. Never leaves the Host.

**Bridge**:
The `luvia-host bridge` process sshd forces for a Device. It mints both tokens, authorizes each frame against the Role, and proxies NDJSON to Luvus.
_Avoid_: Proxy, relay, tunnel

### Terminal (secondary surface)

**Pane**:
A Luvus terminal surface inside a workspace. Terminal observe and control target one Pane. The phone reaches a Pane mainly through its Agent; raw terminal is a fallback view.

**Observe**:
A read stream of a Pane's frames. Any Role may observe.

**Terminal control**:
Exclusive write access to a Pane, held on a control stream. Only one holder; a second request yields a control conflict.
_Avoid_: Controlling, taking over, session control

**Control conflict**:
The refusal returned when another holder already has Terminal control of the Pane.

**Stream budget**:
The Host-wide cap of eight concurrent Observe and Terminal control streams, shared by every connected client including the desktop TUI.

### Agents (primary surface)

**Agent**:
A coding agent Luvus is running in a Pane. The unit the phone lists, reads, and answers.

**Transcript**:
The recent text of an Agent's Pane, fetched with `agent.read`. How the phone shows what an Agent is doing without a terminal stream.
_Avoid_: Output, log, terminal

**Blocked**:
An Agent state: it is waiting on a human answer and cannot proceed. Answered with an Agent prompt or Agent keys.
_Avoid_: Waiting, stuck, pending

**Agent prompt**:
A free-text message delivered to an Agent's Pane as one atomic submission. The way to answer a question an Agent asked. Never retried automatically.
_Avoid_: Message, reply, response

**Agent keys**:
One or more keystrokes delivered to an Agent's Pane, used to answer a yes/no approval. Never retried automatically.
_Avoid_: Approval, confirm, accept

**Unconfirmed**:
The state of an Agent prompt, Agent keys, Review note send, or terminal input whose result never arrived. Resolved by re-reading Agent state or the Transcript, never by resending.
_Avoid_: Failed, timed out, pending

### Review

**Diff**:
The Host's current git changes for a workspace, read per file and layer (staged, worktree, untracked, conflict). Read-only from the phone.

**Review note**:
A comment anchored to a line of a Diff, authored on the phone as `external`. Lives on the Host; can be resolved, reopened, or sent.
_Avoid_: Comment, annotation, feedback

**Send notes**:
Delivering one or more open Review notes into an Agent's Pane as a hand-off message. Non-atomic on the Host; never retried automatically.

### Orchestration

**Task**:
A row in the Host's orchestration ledger with a status and a revision. Mutations are guarded by `if_revision`. The phone reads the board and may add, update, or complete a Task after confirmation.

**Lease**:
An exclusive path lock a Task's worker holds. Read-only from the phone.

**Mission**:
The Host-wide dashboard of live and resumable Agents with usage. Distinct from the Task board. Available on Hosts 0.13.4 and newer.
_Avoid_: Overview, dashboard, summary

**Landmine**:
`task.next`: declared read-only by the Host but claims or starts a Task. The phone never calls it.
