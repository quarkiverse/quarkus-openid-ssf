# quarkus-ssf-receiver

[![Build (JVM)](https://github.com/thomasdarimont/quarkus-ssf-receiver/actions/workflows/build.yml/badge.svg)](.github/workflows/build.yml)
[![Native build](https://github.com/thomasdarimont/quarkus-ssf-receiver/actions/workflows/native.yml/badge.svg)](.github/workflows/native.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Quarkus extension that lets a Quarkus app act as a [Shared Signals Framework
(SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
receiver against any compliant SSF transmitter (Keycloak, [caep.dev](https://ssf.caep.dev),
custom).

| | |
|---|---|
| **Status** | Experimental — APIs may change before 1.0. |
| **License** | [Apache-2.0](LICENSE) |
| **Java** | 21+ for the extension, 25 for the included examples |
| **Quarkus** | 3.35.x (floor — see [Compatibility](#compatibility)) |
| **Group ID** | `com.easyssf` |

## What it does

- Accepts inbound SETs via **PUSH** (RFC 8935) — registers a Vert.x route at `/ssf/push`.
- Pulls SETs via **POLL** (RFC 8936) — periodic Vert.x timer; manual `pollNow()` trigger.
- Verifies every SET: JWS signature against the transmitter's JWKS, plus
  `iss` / `iat` / `jti` / `aud` checks per RFC 8417.
- Manages stream lifecycle in two modes:
    - **`TRANSMITTER`** — operator pre-creates the stream; receiver is given a `stream-id`.
    - **`RECEIVER`** — extension calls the transmitter's `configuration_endpoint`
      on startup to discover an existing stream or create a new one.
- Exposes `SsfStreamClient` for the full §8.1.x management surface
  (read / create / patch / replace / delete config, status read+update,
  add/remove subjects, request verification, POLL).
- Optional Micrometer metrics + per-event-type counters.
- Optional outbound bearer-token auth via static token or `quarkus-oidc-client`.

## Layout

| Module | Role |
|---|---|
| [`runtime/`](runtime/) | Extension runtime: config mapping, `SsfEventHandler` SPI, JWKS resolver, SET verifier, push route, POLL scheduler, stream client, alias resolver, metrics SPI. |
| [`deployment/`](deployment/) | Build-time processor — registers beans + REST clients, picks the right `TransmitterTokenProvider` at build time, wires Micrometer when present. Also contains the smoke test (signed SET round-trip with a stub JWKS). |
| [`examples/example-transmitter-managed-stream/`](examples/example-transmitter-managed-stream/) | Runnable Quarkus app — TRANSMITTER mode + PUSH delivery against Keycloak. |
| [`examples/example-receiver-managed-stream/`](examples/example-receiver-managed-stream/) | Runnable Quarkus app — RECEIVER mode against caep.dev (default) or Keycloak (`-Dquarkus.profile=keycloak`, switches to POLL). |

## Consumer SPI

Provide a CDI bean implementing `SsfEventHandler`:

```java
@ApplicationScoped
public class MyHandler implements SsfEventHandler {
    @Override
    public void handle(SsfEventToken event) {
        // RFC 8417 + SSF profile fields:
        //   event.jti(), event.iss(), event.iat()
        //   event.aud()                 — always List<String>, even single-aud SETs
        //   event.events()              — Map<eventTypeURI, payload> (RFC 8417 §2.2)
        //   event.subjectId()           — sub_id object
        //   event.txn()                 — transaction id, may be null
        //   event.additionalProperties()— any unmodelled / future claims
    }
}
```

If you don't, `LoggingSsfEventHandler` is the default — logs `jti` / `iss` /
event-type aliases at INFO.

## Quick start

### Transmitter-managed (operator owns the stream)

```properties
ssf.receiver.transmitter-issuer=https://kc.example/realms/r1
ssf.receiver.stream-management=TRANSMITTER
ssf.receiver.stream-id=<from Keycloak admin>
ssf.receiver.delivery-method=PUSH
ssf.receiver.push.expected-auth-header=Bearer <shared-secret>   # optional
```

### Receiver-managed (extension creates / rediscovers the stream)

```properties
ssf.receiver.transmitter-issuer=https://kc.example/realms/r1
ssf.receiver.stream-management=RECEIVER
ssf.receiver.delivery-method=PUSH                               # or POLL
ssf.receiver.push.delivery-endpoint-url=https://my-app.example/ssf/push
ssf.receiver.expected-audience=https://my-app.example
ssf.receiver.events-requested=\
    https://schemas.openid.net/secevent/caep/event-type/session-revoked
# Optional — defaults shown.
ssf.receiver.receiver-managed.register-stream=true
ssf.receiver.receiver-managed.delete-on-shutdown=false
```

In RECEIVER mode the registrar lists this receiver's existing streams on the
transmitter and reuses one whose `delivery.endpoint_url` (or `aud`) matches
before creating a new one — so a normal restart doesn't accumulate duplicates.

## Usage patterns

Three concrete shapes that cover most real-world receivers. Each is
stand-alone — pick whichever matches your use case and copy.

### 1 · Session / cache invalidator

> "I'm a backend that authenticates users via Keycloak. When the IdP revokes
> a session or rotates credentials, I need to drop my local sessions and
> tokens so the user can't continue with stale state."

```java
@ApplicationScoped
public class SessionRevocationHandler implements SsfEventHandler {

    private static final String SESSION_REVOKED =
            "https://schemas.openid.net/secevent/caep/event-type/session-revoked";
    private static final String CREDENTIAL_CHANGE =
            "https://schemas.openid.net/secevent/caep/event-type/credential-change";

    @Inject SessionStore sessions;
    @Inject TokenCache tokens;

    @Override
    public void handle(SsfEventToken event) {
        String subjectId = (String) event.subjectId().get("sub");
        if (subjectId == null) return;

        if (event.events().containsKey(SESSION_REVOKED)) {
            sessions.invalidateAllFor(subjectId);
        }
        if (event.events().containsKey(CREDENTIAL_CHANGE)) {
            tokens.invalidateAllFor(subjectId);
            sessions.requireReauthFor(subjectId);
        }
    }
}
```

```properties
ssf.receiver.transmitter-issuer=https://kc.example/realms/r1
ssf.receiver.stream-management=TRANSMITTER
ssf.receiver.stream-id=<from Keycloak admin>
ssf.receiver.delivery-method=PUSH
ssf.receiver.expected-audience=https://my-app.example
ssf.receiver.push.expected-auth-header=Bearer ${PUSH_SHARED_SECRET}
```

**Key points:**
- TRANSMITTER-managed: simplest setup, operator creates the stream in Keycloak admin.
- PUSH delivery: lowest latency, requires the receiver to be reachable from the transmitter.
- The handler is **idempotent** by construction — invalidating an already-invalid session is a no-op. That's important because the extension's built-in `jti` dedup is best-effort across restarts (it's in-memory by default).

### 2 · Event forwarder / fan-out

> "I'm an audit hub or SIEM connector. I need to receive SSF events from
> several transmitters and republish them to Kafka / Elasticsearch /
> Datadog without losing any."

```java
@ApplicationScoped
public class KafkaForwarder implements SsfEventHandler {

    @Inject @Channel("ssf-events") Emitter<String> producer;
    @Inject SsfAliases aliases;
    @Inject ObjectMapper json;

    @Override
    public void handle(SsfEventToken event) {
        // Forward the verified token + the alias-resolved issuer so downstream
        // consumers don't have to reproduce the alias lookup.
        Map<String, Object> payload = Map.of(
                "jti", event.jti(),
                "iss", event.iss(),
                "issAlias", aliases.issuerAlias(event.iss()),
                "iat", event.iat().toString(),
                "events", event.events(),
                "subjectId", event.subjectId(),
                "additionalProperties", event.additionalProperties());
        try {
            producer.send(json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("could not serialise SSF event " + event.jti(), e);
        }
    }
}
```

```properties
ssf.receiver.transmitter-issuer=https://kc.example/realms/r1
ssf.receiver.stream-management=RECEIVER
ssf.receiver.delivery-method=POLL
ssf.receiver.alias=audit-hub-prod-eu
ssf.receiver.poll.interval=5s
ssf.receiver.poll.return-immediately=false   # long-poll for low latency
ssf.receiver.events-requested=\
    https://schemas.openid.net/secevent/caep/event-type/session-revoked,\
    https://schemas.openid.net/secevent/caep/event-type/credential-change,\
    https://schemas.openid.net/secevent/caep/event-type/assurance-level-change
```

Plus a **persistent ack store** so unacked events survive receiver restarts:

```java
@ApplicationScoped
public class JdbcSsfPollAckStore implements SsfPollAckStore {
    @Inject DataSource ds;
    // implementation that INSERTs jtis on enqueue,
    // SELECTs+DELETEs FROM ssf_pending_acks on drain,
    // re-INSERTs on requeue.
    // …
}
```

**Key points:**
- RECEIVER-managed: forwarder creates its own subscription on each transmitter.
- POLL delivery: forwarder can sit anywhere reachable to the transmitter (no inbound HTTP needed).
- `ssf.receiver.alias` distinguishes multiple forwarder instances scraped into the same monitoring store (`receiver` tag on `ssf.receiver.events.processed`).
- Custom `SsfPollAckStore` (`@ApplicationScoped`, no `@DefaultBean`) replaces the in-memory deque; the extension picks it up automatically.
- Forwarder MUST be idempotent on the Kafka / SIEM side — the receiver's at-least-once semantics propagate through.

### 3 · Continuous authorization / step-up trigger

> "When the IdP signals an assurance-level change or device-compliance fail,
> I need to mark the user's tokens as needing re-auth before the next
> request — not at the end of the access-token lifetime."

```java
@ApplicationScoped
public class StepUpTrigger implements SsfEventHandler {

    private static final String ASSURANCE_LEVEL_CHANGE =
            "https://schemas.openid.net/secevent/caep/event-type/assurance-level-change";
    private static final String DEVICE_COMPLIANCE =
            "https://schemas.openid.net/secevent/caep/event-type/device-compliance-change";

    @Inject SubjectFlags flags;

    @Override
    public void handle(SsfEventToken event) {
        if (event.events().containsKey(ASSURANCE_LEVEL_CHANGE)
                || event.events().containsKey(DEVICE_COMPLIANCE)) {
            String sub = (String) event.subjectId().get("sub");
            if (sub != null) {
                flags.markRequiresStepUp(sub, Duration.ofMinutes(15));
            }
        }
    }
}
```

```properties
# Same minimum config as pattern 1, plus:
ssf.receiver.alias=auth-edge

# Tighter poll cadence if PUSH isn't an option:
ssf.receiver.delivery-method=POLL
ssf.receiver.poll.interval=2s
ssf.receiver.poll.return-immediately=false
```

**Key points:**
- Latency-sensitive — PUSH preferred, long-poll second choice.
- `SubjectFlags` is the application's own state store (Redis / Caffeine); the SSF extension stays out of authorization logic.
- The `quarkus-oidc` resource-server filter then checks `flags.requiresStepUp(sub)` on each request and 401s if true. SSF and OIDC compose cleanly — neither knows about the other.

## Compatibility

| | Tested | Floor | Notes |
|---|---|---|---|
| **Quarkus** | 3.35.x | 3.35.0 | Earlier versions may work but aren't tested. CI matrix in `.github/workflows/build.yml` is the source of truth — extend the `quarkus:` axis to add an LTS pin. |
| **Java (extension)** | 21, 25 | 21 | The runtime + deployment artifacts compile under `--release 21`. Consumers may run on any 21+. |
| **Java (examples)** | 25 | 25 | Examples target the latest Java to exercise modern APIs; if your Java version is lower, copy the example into a 21-targeted module. |
| **Native image** | GraalVM 25 (Mandrel-equivalent) | — | The extension compiles native; the built-in CI workflow validates this on every PR for both example apps. |

## Stream management

In both modes, the extension fetches and logs the configured stream's
configuration + status at startup so the operator gets an immediate
confirmation:

```
INFO  Transmitter-managed mode: using stream stream_id=… (status=enabled, delivery-method=PUSH, push_endpoint=…, events_delivered=[CaepSessionRevoked, …])
INFO  Receiver-managed mode: created stream stream_id=… (status=enabled, delivery-method=POLL, poll_endpoint=…, events_delivered=[…])
```

Disable per mode if outbound credentials aren't available:
```properties
ssf.receiver.transmitter-managed.probe-on-startup=false
ssf.receiver.receiver-managed.register-stream=false
```

### Startup resilience (RECEIVER mode)

In RECEIVER mode, discover-or-create runs on a **background virtual thread**
with exponential backoff (1s → 2s → 4s → 8s → 16s → 30s, capped). The JVM
never blocks on transmitter availability:

- The push route is registered immediately, so inbound SETs work as soon as
  the transmitter starts pushing — even if registration is still in flight.
- Each failed attempt logs a one-line WARN; full stack at DEBUG.
- The Dev UI's "Stream Management" page shows
  *"Stream registration in progress…"* until the background loop succeeds,
  then auto-loads the rest.
- The retry loop exits cleanly on `ShutdownEvent`.

The pinned-`stream-id` path stays synchronous (cheap; failures already
tolerated as best-effort warnings).

## jti deduplication

Inbound SETs run through `SsfJtiDedupStore.seenBefore(event)` after
verification but before the application's `SsfEventHandler` is invoked, on
both PUSH and POLL paths. Duplicates are silently dropped (PUSH still 202s,
POLL still acks the jti — the SET *was* received and processed; we just
don't redeliver to the handler).

```properties
# Enabled by default. Disable if your handler is naturally idempotent and
# the extra lookup is wasted work.
ssf.receiver.dedup.enabled=true
ssf.receiver.dedup.capacity=10000
```

The default `InMemorySsfJtiDedupStore` is a bounded `LinkedHashMap` with
FIFO eviction — at-least-once across receiver restarts. For exactly-once
across restarts, ship your own `@ApplicationScoped SsfJtiDedupStore`
backed by a database / Redis / etc. (no `@DefaultBean`); the extension
swaps it in automatically.

The dedup key is **`iss::jti`**, not just `jti` — RFC 8417 §2.2 scopes
`jti` uniqueness to the issuer, so a receiver consuming from multiple
transmitters that mint colliding jti values stays correct.

Metrics:
- `ssf.receiver.dedup.skipped` counter, tagged by `delivery` ∈ {`push`, `poll`}.
- `ssf.receiver.dedup.store.size` gauge.

## Delivery: PUSH

Push endpoint defaults to `POST /ssf/push` (relative to `quarkus.http.root-path`);
override via `ssf.receiver.push.endpoint-path`.

| Condition | Response |
|---|---|
| `expected-auth-header` configured and inbound `Authorization` mismatches | `401` |
| JWT parse, signature, or claim validation fails (missing `iss`/`iat`/`jti`, `iss != transmitter-issuer`, `aud` does not contain `expected-audience` when configured) | `400` |
| Verified | `202` — SET is dispatched to the handler asynchronously on a virtual thread; handler exceptions are logged but never become 5xx |

JWKS is fetched lazily (Nimbus `JWKSource`), cached for 5 min, refreshed once
on `kid` miss before failing.

## Delivery: POLL (RFC 8936)

```properties
ssf.receiver.delivery-method=POLL

# Optional — defaults shown.
ssf.receiver.poll.interval=30s
ssf.receiver.poll.start-delay=0s             # delay before the first poll
ssf.receiver.poll.auto-start=true            # false → drive polling manually
ssf.receiver.poll.max-events=100
ssf.receiver.poll.return-immediately=true    # false → long-poll
ssf.receiver.poll.drain-on-poll=true         # if moreAvailable=true, keep polling
ssf.receiver.poll.timeout=30s
# Override only if the stream's delivery.endpoint_url isn't queryable:
#ssf.receiver.poll.endpoint-url=https://transmitter.example/ssf/poll/<stream>
```

The poll endpoint URL is normally **discovered** from the stream's
`delivery.endpoint_url` (returned by the configuration endpoint). For
receiver-managed streams the transmitter assigns it during `createStream`.

### Acknowledgments

After a SET has been verified and the handler returns without throwing, its
`jti` is enqueued via `SsfPollAckStore` and sent in the next poll's `ack`
array (RFC 8936 §2.1).

The default `InMemorySsfPollAckStore` is a concurrent deque that's lost on
restart — at-least-once delivery, since unacked SETs will be redelivered. For
exactly-once durability, provide your own `@ApplicationScoped` `SsfPollAckStore`
(no `@DefaultBean`) backed by a database, Redis, the filesystem, …; the
extension swaps it in automatically.

### Manual polling

`ssf.receiver.poll.auto-start=false` keeps the poller idle. Inject `SsfPoller`
and drive it from app code:

```java
@Inject SsfPoller poller;
// from a REST endpoint, scheduled job, message handler, …:
poller.pollNow();
```

## Outbound auth to the transmitter

| Configured | Provider | Notes |
|---|---|---|
| `ssf.receiver.transmitter-access-token` set | `StaticTransmitterTokenProvider` | Sends the literal value as `Authorization: Bearer …`. For transmitters like caep.dev that issue long-lived tokens out-of-band. **Wins over OIDC when both are eligible.** |
| `quarkus-oidc-client` on the classpath | `OidcTransmitterTokenProvider` | Fetches a token via `quarkus.oidc-client.*` (typically `client_credentials`). |
| Neither | `NoopTransmitterTokenProvider` | No `Authorization` header. Fine for purely local stubs / public metadata. |

The decision is made at **build time** — the deployment processor reads
`ssf.receiver.transmitter-access-token` under the active profile and logs which
provider it registered. Dev mode rebuilds on profile change so this is seamless;
for a packaged jar, repackage with the desired profile.

JWKS, metadata, and the configuration endpoint (when called for stream
discovery) are unauthenticated by SSF/OIDC convention — the extension does
**not** attach an `Authorization` header on those calls.

## Metrics (optional)

Add `quarkus-micrometer-registry-prometheus` (or another registry extension)
and the extension publishes the following meters under `ssf.receiver.*`. Without
it, a no-op SPI is in effect — zero behavior change.

| Meter | Type | Tags |
|---|---|---|
| `ssf.receiver.push.accepted` | counter | — |
| `ssf.receiver.push.rejected` | counter | `reason` ∈ {`auth`, `body`, `verify`} |
| `ssf.receiver.push.handler.errors` | counter | — |
| `ssf.receiver.poll.cycles` | timer | `outcome` ∈ {`success`, `failure`} |
| `ssf.receiver.poll.events.received` | counter | — |
| `ssf.receiver.poll.events.handled` | counter | — |
| `ssf.receiver.poll.events.failed` | counter | `reason` ∈ {`verify`, `handler`} |
| `ssf.receiver.poll.acks.sent` | counter | — |
| `ssf.receiver.poll.ack.queue.depth` | gauge | — |
| `ssf.receiver.events.processed` | counter | `event`, `iss`, `receiver`, `delivery` ∈ {`push`, `poll`} |
| `ssf.receiver.dedup.skipped` | counter | `delivery` ∈ {`push`, `poll`} |
| `ssf.receiver.dedup.store.size` | gauge | — |

`events.processed` is the per-event-type, per-transmitter, per-delivery-mode
counter — the tags run through `SsfAliases` so URIs become readable names.

Scrape at `/q/metrics`.

## Aliases (`SsfAliases`)

Three independent alias domains keep tag values short and stable:

```properties
# Event-type URI → short alias (built-in defaults exist for SSF spec types)
ssf.receiver.event-aliases.CaepSessionRevoked=https://schemas.openid.net/secevent/caep/event-type/session-revoked
ssf.receiver.event-aliases.CaepCredentialChange=https://schemas.openid.net/secevent/caep/event-type/credential-change

# Transmitter issuer URL → short alias (no built-ins)
ssf.receiver.issuer-aliases.CaepDev=https://ssf.caep.dev
ssf.receiver.issuer-aliases.KeycloakRealm=https://kc.example/realms/r1

# This receiver's own short name — surfaces as the `receiver` tag.
# Falls back to expected-audience, then "unknown".
ssf.receiver.alias=my-receiver
```

Built-in event-type aliases:
- `…/secevent/ssf/event-type/verification` → `SsfStreamVerification`
- `…/secevent/ssf/event-type/stream-updated` → `SsfStreamUpdated`

`SsfAliases` is a regular CDI bean — `@Inject` it into your own resources / log
lines / handlers if you want the same naming outside metrics. Unknown URIs
fall through unchanged so nothing is ever lost.

## Disable switch

```properties
ssf.receiver.enabled=false
```

When `false`, every startup observer (validator, push route, probe, registrar,
POLL scheduler) becomes a no-op. `SsfStreamClient`, metrics, and `SsfAliases`
stay wired in CDI — application code that touches them directly still works,
but nothing happens automatically. Useful for `%test`, `%dev`-without-transmitter,
or runtime kill-switching via env var.

## Build

```sh
mvn -DskipTests install                  # build + install all artifacts
mvn -pl deployment test                  # smoke test (signed SET round-trip)
mvn -pl examples/example-transmitter-managed-stream quarkus:dev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev
```

## Out of scope (today)

- Built-in persistence — receiver-managed stream id is re-discovered on
  restart, the dedup window and POLL ack queue are in-memory by default.
  Both have SPIs (`SsfJtiDedupStore`, `SsfPollAckStore`) that consumers
  can implement against durable storage.
- Per-event-type CAEP / RISC parsing — `SsfEventToken` exposes the raw
  `events` map plus `additionalProperties` for forward-compat claims;
  consumers parse what they care about.
- Multi-transmitter receivers — the extension currently subscribes to a
  single transmitter per app. Multi-transmitter consumers run multiple
  receiver instances with distinct `ssf.receiver.alias` values.
