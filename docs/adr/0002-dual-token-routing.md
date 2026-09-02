# ADR 0002: The bridge mints a session token and an action token; neither reaches the Device

Status: accepted (2026-09-02)

## Context

On Luvus 0.13.2, `session.snapshot` and `events.subscribe` required `admin`
(empirical finding F1), yet the Device must never hold `admin`. At HEAD
(0.13.4) both are `required_scope = "read"` (`src/api/capabilities.rs:276-289`),
so the original forcing function is gone for new Hosts, but older Hosts remain
in the field and `uhp.token.create` itself stays `admin`.

## Decision

`luvia-host bridge` (`host/src/role.rs`, `host/src/uhp.rs`) mints two ephemeral
tokens via `uhp.token.create` as the local owner:

- Session token: `read,admin`. Spent only on `session.snapshot` and
  `events.subscribe`. Exists so the bridge works on 0.13.2 Hosts.
- Action token: exactly the Role's scopes (Observer `read`; Controller
  `read,workspace,agent,terminal,orchestration`). Spent on every other method.

The bridge routes by method name; the Device's frames carry no `auth` field
and cannot choose a token. Both tokens are memory-only on the Host, TTL-bound,
and revoked on bridge exit.

## Alternatives rejected

- Give the Device a token directly: leaks a Luvus credential off-host and
  cannot be scoped below `read` for snapshot on 0.13.2.
- Use `luvus uhp access` (#231): its Control allow-list is too narrow for the
  UHP-first surface (ADR 0001) and adds a second pairing flow.
- Drop the session token now that F1 is fixed: breaks 0.13.2 Hosts for no
  Device-visible gain. Revisit when 0.13.4 is the installed floor.

## Consequences

- `luvia-host` needs no change for 0.13.4; the `read,admin` session token is
  simply redundant there.
- `uhp.token.*`, `config.get`, and other `admin` reads stay unreachable from
  the Device by construction.
