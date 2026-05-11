# Example — receiver-managed stream (PUSH or POLL, against any SSF transmitter)

Standalone Quarkus app that consumes the `quarkus-openid-ssf-receiver` extension in
**`stream-management=RECEIVER`** mode. The app creates (or re-discovers) its
own SSF stream on the transmitter at startup — no operator setup beyond an
OAuth client (or a long-lived bearer token).

The example ships **three configurations**: a neutral default that's driven
entirely by environment variables, plus two profile overlays that pre-fill
the env-vars for two well-known transmitters.

| Configuration | Activate with | Transmitter | Auth | Delivery |
|---|---|---|---|---|
| _default_ | `mvn quarkus:dev` (+ env vars) | _whatever you point it at_ | Static bearer token (if `SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN` is set) or OIDC | PUSH |
| `caepdev` | `-Dquarkus.profile=caepdev` | [caep.dev](https://ssf.caep.dev) | Static bearer token | PUSH (default) or POLL via `-D` overrides |
| `keycloak` | `-Dquarkus.profile=keycloak` | The [transmitter-managed example](../example-transmitter-managed-stream/)'s realm | OIDC `client_credentials` | POLL |

Each profile lives in its own sibling file (`application-caepdev.properties`,
`application-keycloak.properties`) — Quarkus loads them automatically when
the matching profile is active. The default `application.properties` is left
neutral on purpose so it stays a useful template.

> **Heads-up.** The `/events`, `/streams`, `/transmitter` endpoints are
> unauthenticated by design — developer aids, not production surfaces.

## What it demonstrates

- **Discover-or-create** lifecycle in `ReceiverManagedStreamRegistrar`:
    1. `GET <configuration_endpoint>` (no `stream_id`) lists streams this
       receiver already owns on the transmitter.
    2. If a stream's `delivery.endpoint_url` matches our configured push URL
       — or its `aud` contains `expected-audience` — its `stream_id` is
       **reused**.
    3. Otherwise a new stream is **created** with the configured
       `delivery.endpoint_url`, `events_requested`, and optional `description`.
- **Build-time auth selection**: the deployment processor picks
  `StaticTransmitterTokenProvider` when `transmitter-access-token` is set
  (`caepdev` profile, or whenever you set `SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN`
  in the default profile) and `OidcTransmitterTokenProvider` when it isn't and
  `quarkus-oidc-client` is on the classpath (`keycloak` profile).
- **Both delivery modes**: PUSH in the default and `caepdev` profiles, POLL
  (RFC 8936) in the `keycloak` profile with a periodic Vert.x timer + ack queue.
- **Manual poll trigger** (`POST /streams/default/poll`) — useful for demos
  and works only in POLL mode.
- **`CapturedEvent` wrapper** — each captured `SsfEventToken` is exposed via
  REST with the per-event-type **payload** keyed by alias, plus the raw token
  for inspection.
- **Aliases** (`SsfAliases`) — short, readable names for event-type URIs and
  issuer URLs in logs and metric tags.

### Endpoints (port `28080`)

| | |
|---|---|
| `GET  /events/recent-events` | Last 50 captures (alias-keyed `payloads` + raw token) |
| `GET  /events/latest`        | Most recent capture |
| `GET  /transmitter/registration` | The `stream_id` the registrar discovered or created |
| `GET  /transmitter/metadata` | Parsed `.well-known/ssf-configuration` |
| `GET  /streams`              | All streams this receiver owns on the transmitter |
| `GET  /streams/default`      | Live config for our auto-registered stream |
| `GET  /streams/default/status` | Live status |
| `POST /streams/default/status?status=paused&reason=…` | Pause / enable / disable |
| `POST /streams/default/verify[?state=…]` | Trigger a Verification SET |
| `POST /streams/default/poll` | Run one POLL cycle now (POLL mode only — 409 in PUSH mode) |
| `DELETE /streams/default`    | Delete the stream on the transmitter |
| `GET  /q/metrics`            | Prometheus scrape endpoint (Micrometer) |
| `POST /ssf/push`             | The push endpoint (registered by the extension, PUSH mode only) |

## Running

### Environment variables (default profile)

The default profile is intentionally empty of transmitter specifics — set the
env vars below and `mvn quarkus:dev` works against any SSF-compliant transmitter:

| Variable | Required? | Purpose |
|---|---|---|
| `SSF_RECEIVER_TRANSMITTER_ISSUER` | yes | The transmitter's issuer URL — drives `.well-known/ssf-configuration` discovery and JWKS fetch. |
| `SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN` | optional | Long-lived bearer token. If set, build-time selects `StaticTransmitterTokenProvider`; if unset, OIDC is used (assumes `quarkus.oidc-client.*` is configured via a profile). |
| `SSF_RECEIVER_PUSH_DELIVERY_URL` | optional | Public URL the transmitter should POST SETs to. Defaults to `http://localhost:28081/ssf/push`; override with your tunnel URL for caep.dev / public transmitters. |
| `SSF_RECEIVER_EXPECTED_AUDIENCE` | optional | The `aud` claim this receiver expects on inbound SETs. Defaults to the placeholder `https://my.expected.audience.com`. |

### `caepdev` profile — caep.dev / PUSH

Quickest way to see the extension light up against a real transmitter.

#### 1. Get a caep.dev access token

caep.dev issues bearer tokens out-of-band — see <https://ssf.caep.dev>.
Export it:

```sh
export SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN=<your-caep-dev-token>
```

#### 2. Make the push endpoint reachable

caep.dev needs to reach `quarkus.openid-ssf.receiver.push.delivery-endpoint-url` to deliver
SETs. For local dev, expose it via a tunnel:

```sh
cloudflared tunnel --url http://localhost:28080
# → public URL printed; use that as the delivery URL
export SSF_RECEIVER_PUSH_DELIVERY_URL=https://<your-tunnel>.trycloudflare.com/ssf/push
```

If you only want to exercise the management-side flow (create / read / verify
/ delete stream) and don't care about actually receiving SETs, leave
`SSF_RECEIVER_PUSH_DELIVERY_URL` unset — the default
`http://localhost:28081/ssf/push` is registered with caep.dev but won't be
reachable.

#### 3. Start

```sh
mvn -pl examples/example-receiver-managed-stream quarkus:dev -Dquarkus.profile=caepdev
```

Expected boot log on first run:

```
INFO  quarkus.openid-ssf.receiver.transmitter-access-token configured — registering StaticTransmitterTokenProvider
INFO  Receiver-managed mode: no existing stream matched (delivery-method=PUSH) — creating a new one
INFO  Receiver-managed mode: created stream stream_id=… (status=enabled, delivery-method=PUSH, push_endpoint=…, events_delivered=[CaepSessionRevoked, CaepCredentialChange])
```

On subsequent runs the discover step finds and reuses the stream:

```
INFO  Receiver-managed mode: reusing existing stream stream_id=… (status=enabled, …)
```

#### 4. Confirm

```sh
curl -s localhost:28080/transmitter/registration | jq
curl -s localhost:28080/streams/default          | jq
curl -s -X POST localhost:28080/streams/default/verify    # triggers a Verification SET
curl -s localhost:28080/events/recent-events     | jq     # see what arrived
```

### `caepdev` profile — caep.dev / POLL (no tunnel needed)

Same profile, no public push endpoint required. The receiver pulls SETs from
caep.dev on a periodic Vert.x timer; nothing reaches back to your machine, so
this works behind NAT, on a coffee-shop wifi, in CI, etc.

#### 1. Get a caep.dev access token

Same as the PUSH path — register at <https://ssf.caep.dev>, copy the token:

```sh
export SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN=<your-caep-dev-token>
```

#### 2. Start with POLL delivery overrides

```sh
mvn -pl examples/example-receiver-managed-stream quarkus:dev \
    -Dquarkus.profile=caepdev \
    -Dquarkus.openid-ssf.receiver.delivery-method=POLL \
    -Dquarkus.openid-ssf.receiver.poll.interval=5s
```

The `caepdev` profile pins the issuer and access-token; the `-D` overrides
flip the delivery mode. caep.dev assigns the poll URL when the registrar
creates the stream, so the extension discovers it from the returned stream
config — no `push.delivery-endpoint-url` to set.

Expected boot log on first run:

```
INFO  quarkus.openid-ssf.receiver.transmitter-access-token configured — registering StaticTransmitterTokenProvider
INFO  Receiver-managed mode: no existing stream matched (delivery-method=POLL) — creating a new one
INFO  Receiver-managed mode: created stream stream_id=… (status=enabled, delivery-method=POLL, poll_endpoint=https://ssf.caep.dev/…, events_delivered=[CaepSessionRevoked, CaepCredentialChange])
INFO  POLL delivery: scheduling poll of https://ssf.caep.dev/… every 5000ms (start-delay=0ms, max-events=100, return-immediately=true)
```

#### 3. Trigger and confirm

Fire an event from the caep.dev UI (or hit a transmitter endpoint that emits
one), then watch it arrive on the next poll tick:

```sh
curl -s localhost:28080/streams/default        | jq    # stream config + delivery.endpoint_url
curl -s -X POST localhost:28080/streams/default/poll   # force one cycle now instead of waiting
curl -s localhost:28080/events/recent-events   | jq    # see what arrived
```

> **Production cadence.** `poll.interval=5s` is for the demo. A sensible
> production setup is either `return-immediately=false` (long-poll, the
> transmitter holds the request open until events show up — lowest latency
> with the fewest requests) or a longer interval like `30s` if your
> transmitter doesn't support long-poll.

### `keycloak` profile — Keycloak / POLL

```sh
export OIDC_ISSUER_URL=https://your-kc/realms/ssf-poc
export SSF_RECEIVER_CLIENT_ID=quarkus-openid-ssf-receiver
export SSF_RECEIVER_CLIENT_SECRET=<oidc-client-secret>
export SSF_RECEIVER_TRANSMITTER_ISSUER=https://your-kc/realms/ssf-poc
export SSF_RECEIVER_ISSUER_ALIASES_KEYCLOAKSSFPOC=https://your-kc/realms/ssf-poc

mvn -pl examples/example-receiver-managed-stream quarkus:dev -Dquarkus.profile=keycloak
```

Profile-specific overrides live in [`application-keycloak.properties`](src/main/resources/application-keycloak.properties).
Notable differences from default:
- `transmitter-access-token=` (empty, defensive override) → forces OIDC selection
  at build time even if `SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN` is set globally.
- `delivery-method=POLL` + `poll.*` settings — no tunnel needed; the receiver
  pulls from the transmitter.
- Outbound auth: the file ships with `quarkus.oidc-client.*` **active** (so
  the OIDC-backed `TransmitterTokenProvider` is selected by default) and the
  alternative `quarkus.openid-ssf.receiver.oauth2.*` block commented out as a reference for
  the self-contained `Oauth2TransmitterTokenProvider`. To switch, comment
  the OIDC block and uncomment the Oauth2 one — both read the same
  `SSF_RECEIVER_CLIENT_ID` / `SSF_RECEIVER_CLIENT_SECRET` / `OIDC_ISSUER_URL`
  env vars.

Manual POLL trigger (works alongside the periodic timer):

```sh
curl -s -X POST localhost:28080/streams/default/poll
# → {"result":"ok"}  + recent-events updates with whatever was waiting
```

> **Build-time vs runtime:** the Static-vs-OIDC `TransmitterTokenProvider`
> decision is made at build time. Dev mode rebuilds on profile change so this
> is seamless; for a packaged jar, repackage with the desired profile
> (`mvn package -Dquarkus.profile=keycloak`).

### Adding a new transmitter

The default profile is meant as a copy-paste starting point — drop a
`application-<name>.properties` into `src/main/resources/`, fill in the bits
that differ from the default (issuer, auth, delivery method), and run with
`-Dquarkus.profile=<name>`. The two existing profile files are short enough
to read in full as templates:

- [`application-caepdev.properties`](src/main/resources/application-caepdev.properties)
  — minimal: just issuer + access-token + alias.
- [`application-keycloak.properties`](src/main/resources/application-keycloak.properties)
  — POLL delivery + full OIDC client configuration.

Edge cases worth knowing:
- **No-auth transmitter (purely local stub):** clear both
  `transmitter-access-token` and the OIDC config — the no-op token provider
  sends no `Authorization` header.
- **Receiver-managed + POLL:** the transmitter assigns the poll URL during
  `createStream`; the extension discovers it from the stream config, so
  `push.delivery-endpoint-url` is irrelevant in POLL mode.

## Metrics

`quarkus-micrometer-registry-prometheus` is on the classpath, so meters are
scrapable at `/q/metrics`:

```sh
curl -s localhost:28080/q/metrics | grep ^ssf_receiver_
```

Sample series with the example's configured aliases:

```
ssf_receiver_events_processed_total{event="CaepSessionRevoked",iss="CaepDev",receiver="quarkus-openid-ssf-receiver",delivery="push"} 7.0
ssf_receiver_poll_cycles_seconds_count{outcome="success"} 24.0
ssf_receiver_poll_ack_queue_depth 0.0
```

## Disable

If you just want to start the app without any SSF activity (e.g. iterating on
a different REST resource locally):

```sh
mvn -pl examples/example-receiver-managed-stream quarkus:dev -Dquarkus.openid-ssf.receiver.enabled=false
```

The CDI beans stay wired (so app code that touches `SsfStreamClient` directly
still works), but no startup probe / registrar / poller runs.

## What this example does *not* demonstrate

- Persistence — events are in-memory only; the stream id is re-discovered on
  every restart. A real receiver would back `SsfPollAckStore` with durable
  storage and provide its own `SsfJtiDedupStore` instead of the default
  in-memory one.
- Per-event-type CAEP / RISC payload parsing — `CapturedEvent.payloads()`
  returns the raw map keyed by alias for the demo to display.
