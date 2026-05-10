# Example — transmitter-managed stream (Keycloak / PUSH)

Standalone Quarkus app that consumes the `quarkus-ssf-receiver` extension in
**`stream-management=TRANSMITTER`** mode against a Keycloak SSF transmitter,
using **PUSH** delivery (RFC 8935). The operator pre-creates the stream in
Keycloak; this app is configured with the resulting `stream-id`.

> **Heads-up.** All `/events`, `/streams`, `/transmitter` endpoints are
> unauthenticated by design — they're developer aids to confirm end-to-end SSF
> delivery, not production surfaces.

## What it demonstrates

- Implementing `SsfEventHandler` (`CapturingSsfEventHandler`) that buffers the
  last 50 received events in a bounded `ConcurrentLinkedDeque`.
- Wrapping each captured `SsfEventToken` in a demo-friendly `CapturedEvent`
  record that surfaces the per-event-type **payload** keyed by alias rather
  than full URI — easier for screenshots / browser inspection.
- Reading the live stream config + status via `SsfStreamClient`.
- Outbound auth via OIDC `client_credentials` (`quarkus-oidc-client`).
- The startup probe confirming the stream exists and is enabled before the
  receiver accepts any traffic.

### Endpoints (port `28080`)

| | |
|---|---|
| `GET  /events/recent-events` | Last 50 captured events (alias-keyed payloads) |
| `GET  /events/latest`        | Most recent capture only |
| `GET  /streams/default`      | Live stream configuration from the transmitter |
| `GET  /streams/default/status` | Live stream status |
| `POST /streams/default/status?status=paused&reason=…` | Pause / enable / disable |
| `POST /streams/default/verify[?state=…]` | Trigger a Verification SET round-trip |
| `POST /streams/default/subjects/add` | Add a subject (`{"subject":{"format":"…","email":"…"},"verified":true}`) |
| `POST /streams/default/subjects/remove` | Remove a subject |
| `GET  /transmitter/metadata` | Parsed `.well-known/ssf-configuration` document |
| `POST /ssf/push` | The push endpoint (registered by the extension) |

## Running

### 1. Configure Keycloak

You need a Keycloak realm with an OIDC client configured as an SSF receiver,
and a stream with this app's push endpoint as `delivery.endpoint_url`. Manual
admin-side setup — this README intentionally does not spin up Keycloak via
docker-compose / Dev Services so the example stays standalone.

See <https://www.keycloak.org/docs/latest/server_admin/#shared-signals-framework>
for the realm setup.

The stream's push endpoint must be reachable from Keycloak. For local dev,
Keycloak should target `http://localhost:28080/ssf/push` (or use a tunnel if
Keycloak is hosted).

### 2. Edit `src/main/resources/application.properties`

```properties
ssf.receiver.transmitter-issuer=https://<your-keycloak>/realms/<realm>
ssf.receiver.stream-management=TRANSMITTER
ssf.receiver.stream-id=<stream id from Keycloak admin>
ssf.receiver.delivery-method=PUSH
ssf.receiver.expected-audience=<the audience your stream issues SETs to>

# Optional — enforce a shared secret on inbound pushes
#ssf.receiver.push.expected-auth-header=Bearer <shared-secret>

# Outbound auth for /streams/* and the startup probe
quarkus.oidc-client.auth-server-url=https://<your-keycloak>/realms/<realm>
quarkus.oidc-client.client-id=<oidc-client-id>
quarkus.oidc-client.credentials.secret=<secret>
quarkus.oidc-client.grant.type=client
quarkus.oidc-client.scopes=ssf.read,ssf.manage
```

`ssf.receiver.transmitter-metadata-url` and `transmitter-jwks-url` are
auto-discovered from the issuer; override only if your Keycloak doesn't expose
the standard `<issuer>/.well-known/ssf-configuration` location.

### 3. Run

```sh
mvn -pl examples/example-transmitter-managed-stream quarkus:dev
```

Expected boot log:

```
INFO  Registering SSF push endpoint at /ssf/push
INFO  Transmitter-managed mode: using stream stream_id=… (status=enabled, delivery-method=PUSH, push_endpoint=…, events_delivered=[CaepSessionRevoked, …])
```

### 4. Confirm delivery

Trigger an event in Keycloak that the stream subscribes to (revoke a session,
log a user out — whichever `events_delivered` includes):

```sh
curl -s localhost:28080/events/recent-events | jq
curl -s localhost:28080/events/latest        | jq
```

Each entry contains `jti`, `iss` (alias-resolved), `iat`, `aud`, `txn`,
`subjectId`, the alias-keyed `payloads` map, and the raw `SsfEventToken`.

You can also force a round-trip without waiting for a real event:

```sh
curl -s -X POST localhost:28080/streams/default/verify | jq
# → returns the random `state` used; matching Verification SET should appear
#   in /events/latest seconds later
```

## Metrics

`quarkus-micrometer-registry-prometheus` is on the classpath, so meters are
scrapable at `http://localhost:28080/q/metrics`. Filter for the SSF series:

```sh
curl -s localhost:28080/q/metrics | grep ^ssf_receiver_
```

The `event-aliases` and `issuer-aliases` configured in `application.properties`
become readable Micrometer tag values on `ssf_receiver_events_processed_total`.

## What this example does *not* demonstrate

- Receiver-managed streams — see [`example-receiver-managed-stream`](../example-receiver-managed-stream/).
- POLL delivery — same example covers it under the `keycloak` profile.
- Persistence (events are in-memory only).
- Replay protection / `jti` deduplication.
- Per-event-type CAEP / RISC parsing — `SsfEventToken.events()` and
  `additionalProperties()` give the consumer the raw map.
