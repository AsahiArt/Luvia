# Push relay contract

Luvia's SSH link dies when the phone suspends, so an agent that turns
`blocked` or an ACP agent asking for permission would otherwise wait until
the user reopens the app. The push relay closes that gap with a deliberately
dumb, content-free wake:

```
host (luvia-host watch / bridge)  --POST /v1/wake-->  relay  --APNs / UnifiedPush / FCM-->  phone
```

The relay never sees terminal text, prompts, tool calls or host names. It
sees a push token, a wake kind and a count. Self-hosting the relay is a
first-class path; the hosted one is the same binary with billing in front.

## 1. Phone registration

New bridge-intercepted UHP methods (never forwarded to Luvus/Herdr):

| Method | Params | Result | Role |
| --- | --- | --- | --- |
| `luvia.push.register` | `{ "kind": "apns" \| "unifiedpush" \| "fcm", "token": string, "environment"?: "sandbox" \| "production" }` | `{ "registered": true }` | any |
| `luvia.push.unregister` | `{}` | `{ "registered": false }` | any |

- `token` is the raw APNs device token (lowercase hex), the UnifiedPush
  endpoint URL, or the FCM registration token. Max 4 KiB.
- Stored on the device grant: `Grant.push: Option<PushRegistration { kind, token, environment, updated_at }>` in `~/.config/luvia/host/devices/<id>.json`.
- Advertised in `uhp.capabilities` as `luvia.push.register` / `luvia.push.unregister` **only when** `~/.config/luvia/host/relay.json` exists. Phones gate on `HostCapabilities.push`.

## 2. Host → relay

`~/.config/luvia/host/relay.json`:

```json
{ "url": "https://relay.example.com", "key": "<bearer key>" }
```

`POST {url}/v1/wake` with `Authorization: Bearer <key>` and body:

```json
{
  "targets": [{ "kind": "apns", "token": "…", "environment": "production" }],
  "wake": "blocked" | "permission" | "done",
  "count": 2,
  "host": "a1b2c3d4",
  "sent_at": 1726500000
}
```

- `host` is the first 8 hex chars of `sha256(hostname)`; it lets the phone
  collapse notifications per host without the relay learning the name.
- Response `200 {"accepted": n, "rejected": [{"token": "...", "reason": "gone"}]}`.
  A `gone` token is removed from the grant by the host.
- Host-side dedupe: at most one wake of the same `wake` kind per device per
  60 s; a rising `count` inside the window is not re-sent.

Producers:

- `luvia-host watch` — long-running daemon. Subscribes to the running Luvus
  session's `events.subscribe` with a locally minted token (owner role,
  no phone involved), tracks the blocked-agent count, and posts `blocked`
  when it rises from 0 and `done` when every task on the board is done.
  Reconnects with backoff when Luvus restarts. Herdr: polls `agent list`
  every 5 s for the same transitions.
- `luvia-host bridge` — posts `permission` directly when an ACP
  `session/request_permission` arrives (the phone may already be
  backgrounded with the SSH channel still up for a few seconds).

## 3. Relay → phone

Relay binary: `luvia-relay` (crate `relay/`). Config via env:

```
LUVIA_RELAY_LISTEN=0.0.0.0:8787
LUVIA_RELAY_KEYS=key1,key2              # accepted bearer keys
LUVIA_RELAY_APNS_TEAM_ID=…
LUVIA_RELAY_APNS_KEY_ID=…
LUVIA_RELAY_APNS_KEY_P8=/path/AuthKey.p8
LUVIA_RELAY_APNS_TOPIC=tech.asahiart.luvia
LUVIA_RELAY_FCM_SERVICE_ACCOUNT=/path/sa.json   # optional
```

Payloads are content-free and localized on device:

- APNs: `{"aps":{"alert":{"title-loc-key":"push.title","loc-key":"push.<wake>","loc-args":["<count>"]},"sound":"default","thread-id":"<host>","interruption-level":"time-sensitive"},"luvia":{"wake":"<wake>","host":"<host>","count":n}}`, `apns-push-type: alert`, `apns-priority: 10`, `apns-topic` from config, `apns-expiration: sent_at+600`.
- UnifiedPush: raw `POST <endpoint>` with body `{"wake":"<wake>","host":"<host>","count":n}`; `TTL: 600`, `Urgency: high`.
- FCM v1: `message.data = {wake, host, count}`, `android.priority = high`.

`GET /healthz` → `200 ok`.

## 4. Phone

- iOS: register with APNs on first host connect after the user enables
  "Wake me for approvals" in host settings; forward the token to every
  connected host with `luvia.push.register`. On receipt show the localized
  alert; tapping opens the host and jumps to Blocked / the ACP permission.
- Android: UnifiedPush connector (no Google dependency). The endpoint URL is
  the token. Notification channel `luvia.wake`; tap opens the host.
- `HostUhp.setPushEnabled(enabled)` → `HostManager.registerPush/unregisterPush`
  on every live host that advertises the capability. Token is cached in the
  platform store and re-sent when a host (re)connects.

## 5. Threat model

- Relay compromise reveals: tokens, wake kinds, counts, host hashes. No
  content. Tokens are per-app and revocable.
- Host → relay key is per host install; rotating it is editing `relay.json`.
- Phone never talks to the relay; only the host does. Nothing in the phone
  needs relay credentials.
