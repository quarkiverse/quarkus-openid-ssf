# Example — transmitter-managed stream (Keycloak / PUSH)

Standalone Quarkus app that consumes the `quarkus-ssf-receiver` extension in
**`stream-management=TRANSMITTER`** mode against a Keycloak SSF transmitter,
using **PUSH** delivery (RFC 8935). The operator pre-creates the stream in
Keycloak's admin console; this app is configured with the resulting
`stream_id` and the OIDC credentials needed for outbound `/streams/*` calls.

The default `application.properties` is **driven entirely by environment
variables** — nothing transmitter-specific is hard-coded, so the file is safe
to commit and the same example can be pointed at any Keycloak realm by
swapping env vars.

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
| `GET  /q/metrics`            | Prometheus scrape endpoint (Micrometer) |
| `POST /ssf/push` | The push endpoint (registered by the extension) |

## Running

### 1. Configure Keycloak

You need a Keycloak realm with:

1. **An OIDC client** with the `client_credentials` grant enabled and the
   `ssf.read` + `ssf.manage` scopes attached. The receiver authenticates with
   this client when calling `/streams/*` on the transmitter.
2. **A pre-created SSF stream** whose `delivery.endpoint_url` points at this
   app's push endpoint (`http://localhost:28080/ssf/push` for local dev, or
   your tunnel URL if Keycloak is remote).

Manual admin-side setup — this README intentionally does not spin up Keycloak
via docker-compose / Dev Services so the example stays standalone. See
<https://www.keycloak.org/docs/latest/server_admin/#shared-signals-framework>
for the realm setup.

### 2. Set the environment variables

| Variable | Required? | Purpose |
|---|---|---|
| `SSF_RECEIVER_TRANSMITTER_ISSUER` | yes | The issuer URL of the Keycloak realm, e.g. `https://kc.example/realms/ssf-poc`. |
| `SSF_RECEIVER_STREAM_ID` | yes | The `stream_id` you got from the Keycloak admin console after creating the stream. |
| `SSF_RECEIVER_TRANSMITTER_METADATA_URL` | usually | **Keycloak quirk:** Keycloak serves the SSF metadata at `<issuer>/.well-known/ssf-configuration` (OIDC-style suffix), but the SSF §7.2 splice rule the extension uses by default puts it at `<host>/.well-known/ssf-configuration<issuer-path>`. Set this env var to the actual Keycloak URL, e.g. `https://kc.example/realms/ssf-poc/.well-known/ssf-configuration`. |
| `SSF_RECEIVER_EXPECTED_AUDIENCE` | optional | The `aud` claim this receiver expects on inbound SETs. Defaults to a placeholder. |
| `SSF_RECEIVER_PUSH_EXPECTED_AUTH_HEADER` | optional | Shared secret enforced on inbound `Authorization`. Uncomment the matching line in `application.properties` to activate. |
| `OIDC_ISSUER_URL` | yes | The OIDC issuer used by `quarkus-oidc-client` to discover the token endpoint — typically the same as `SSF_RECEIVER_TRANSMITTER_ISSUER`. |
| `SSF_RECEIVER_CLIENT_ID` | yes | The OIDC client id this receiver authenticates as. |
| `SSF_RECEIVER_CLIENT_SECRET` | yes | The OIDC client secret. Shared with the receiver-managed example — run both against the same Keycloak client, or against distinct clients by overriding the value in one of the two profiles. |

```sh
export SSF_RECEIVER_TRANSMITTER_ISSUER=https://kc.example/realms/ssf-poc
export SSF_RECEIVER_TRANSMITTER_METADATA_URL=https://kc.example/realms/ssf-poc/.well-known/ssf-configuration
export SSF_RECEIVER_STREAM_ID=<stream-id-from-keycloak-admin>
export SSF_RECEIVER_EXPECTED_AUDIENCE=https://my.expected.audience.com

export OIDC_ISSUER_URL=https://kc.example/realms/ssf-poc
export SSF_RECEIVER_CLIENT_ID=quarkus-ssf-receiver
export SSF_RECEIVER_CLIENT_SECRET=<client-secret>
```

The push endpoint must be reachable from Keycloak. For local dev, point the
stream's `delivery.endpoint_url` at `http://localhost:28080/ssf/push` directly;
for a remote Keycloak, expose this app via a tunnel
(`cloudflared tunnel --url http://localhost:28080`) and use the public URL.

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

## Running alongside the receiver-managed example

Both examples default to port `28080`. To run them side-by-side, override one:

```sh
mvn -pl examples/example-receiver-managed-stream quarkus:dev \
    -Dquarkus.profile=keycloak \
    -Dquarkus.http.port=28081
```

The two examples were designed to coexist against the same Keycloak realm.
They read the same `OIDC_ISSUER_URL` / `SSF_RECEIVER_CLIENT_ID` /
`SSF_RECEIVER_CLIENT_SECRET` env vars by default, so out of the box they
share one OIDC client. To run them against separate clients (independent
audit trails / rate-limits), override the values for one of the two
examples — e.g. via `-Dsmallrye.config.profile=…` profile files or by
exporting different env vars in the shell that runs that example.

## Metrics

`quarkus-micrometer-registry-prometheus` is on the classpath, so meters are
scrapable at `http://localhost:28080/q/metrics`. Filter for the SSF series:

```sh
curl -s localhost:28080/q/metrics | grep ^ssf_receiver_
```

The `event-aliases` and `issuer-aliases` configured in `application.properties`
become readable Micrometer tag values on `ssf_receiver_events_processed_total`.

## Disable

If you just want to start the app without any SSF activity (e.g. iterating on
an unrelated REST resource):

```sh
mvn -pl examples/example-transmitter-managed-stream quarkus:dev -Dssf.receiver.enabled=false
```

The CDI beans stay wired (so app code that touches `SsfStreamClient` directly
still works), but no startup probe / push route registration runs.

## What this example does *not* demonstrate

- Receiver-managed streams — see [`example-receiver-managed-stream`](../example-receiver-managed-stream/).
- POLL delivery — see the receiver-managed example's `keycloak` profile.
- Durable persistence — events are buffered in-memory only. The default in-memory
  `SsfJtiDedupStore` IS active (so duplicate jti's within a single JVM lifetime
  are dropped), but it's lost on restart; a production receiver would supply
  its own SPI implementation backed by a database / Redis.
- Per-event-type CAEP / RISC parsing — `SsfEventToken.events()` and
  `additionalProperties()` give the consumer the raw map.
