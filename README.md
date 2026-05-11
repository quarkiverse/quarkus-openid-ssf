# quarkus-ssf-receiver

[![Build (JVM)](https://github.com/easyssf/quarkus-ssf-receiver/actions/workflows/build.yml/badge.svg)](.github/workflows/build.yml)
[![Native build](https://github.com/easyssf/quarkus-ssf-receiver/actions/workflows/native.yml/badge.svg)](.github/workflows/native.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Quarkus extension that lets a Quarkus app act as a [Shared Signals Framework
(SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
receiver against any spec-compliant SSF transmitter — public providers like
[caep.dev](https://ssf.caep.dev), on-prem / self-hosted IdPs, or custom
implementations. Outbound auth is OAuth2-based (`client_credentials` via
`quarkus-oidc-client`, or a long-lived bearer token), so anything that issues
OAuth2 access tokens fits.

| | |
|---|---|
| **Status** | Experimental — APIs may change before 1.0. |
| **License** | [Apache-2.0](LICENSE) |
| **Java** | 21+ (extension and examples both compile under `--release 21`) |
| **Quarkus** | 3.35.x (floor — see [Compatibility](#compatibility)) |
| **Group ID** | `com.easyssf` |

## What it does

- Accepts inbound SETs via **PUSH** (RFC 8935) — registers a Vert.x route at `/ssf/push`.
- Pulls SETs via **POLL** (RFC 8936) — periodic Vert.x timer; manual `pollNow()` trigger.
- Verifies every SET: JWS signature against the transmitter's JWKS, plus
  `iss` / `iat` / `jti` / `aud` checks per RFC 8417.
- Manages stream lifecycle in two modes:
    - **`RECEIVER`** (default) — extension calls the transmitter's
      `configuration_endpoint` on startup to discover an existing stream or
      create a new one.
    - **`TRANSMITTER`** — operator pre-creates the stream; receiver is given a `stream-id`.
- Exposes `SsfStreamClient` for the full §8.1.x management surface
  (read / create / patch / replace / delete config, status read+update,
  add/remove subjects, request verification, POLL).
- Optional Micrometer metrics + per-event-type counters.
- Outbound auth: static bearer token, self-contained OAuth2
  `client_credentials` (no extra dep), `quarkus-oidc-client`, or no-op.

## Layout

| Module | Role |
|---|---|
| [`runtime/`](runtime/) | Extension runtime: config mapping, `SsfEventHandler` SPI, JWKS resolver, SET verifier, push route, POLL scheduler, stream client, alias resolver, metrics SPI. |
| [`deployment/`](deployment/) | Build-time processor — registers beans + REST clients, picks the right `TransmitterTokenProvider` at build time, wires Micrometer when present. Also contains the smoke test (signed SET round-trip with a stub JWKS). |
| [`examples/example-transmitter-managed-stream/`](examples/example-transmitter-managed-stream/) | Runnable Quarkus app — TRANSMITTER mode + PUSH delivery, env-var-driven, OIDC `client_credentials` for outbound auth. |
| [`examples/example-receiver-managed-stream/`](examples/example-receiver-managed-stream/) | Runnable Quarkus app — RECEIVER mode. Default config is neutral; the `caepdev` profile overlays caep.dev (PUSH + static token), the `keycloak` profile overlays an OIDC + POLL setup. |

## Consumer SPI

Provide a CDI bean implementing `SsfEventHandler`:

```java
@ApplicationScoped
public class MyHandler implements SsfEventHandler {
    @Override
    public void handle(SsfEventContext eventContext) {
        SsfEventToken eventToken = eventContext.eventToken();
        // Raw RFC 8417 + SSF profile fields on the token:
        //   eventToken.jti(), eventToken.iss(), eventToken.iat()
        //   eventToken.aud()                 — always List<String>, even single-aud SETs
        //   eventToken.events()              — Map<eventTypeURI, payload> (RFC 8417 §2.2)
        //   eventToken.subjectId()           — sub_id object
        //   eventToken.txn()                 — transaction id, may be null
        //   eventToken.additionalProperties()— any unmodelled / future claims

        // Alias-aware convenience on the context — accepts either an alias
        // or the full URI, so handler code stays correct regardless of
        // whether aliases are configured. Built-in aliases cover SSF, CAEP,
        // and RISC spec event types out of the box (CaepSessionRevoked,
        // RiscAccountDisabled, …).
        if (eventContext.hasEvent("CaepSessionRevoked")) { /* … */ }
        Map<String, Object> payload = eventContext.eventFor("CaepCredentialChange");
        String issAlias = eventContext.issAlias();
    }
}
```

If you don't, `LoggingSsfEventHandler` is the default — logs `jti` / `iss` /
event-type aliases at INFO.

## Quick start

The fastest way to see the extension light up end-to-end is against the
public [caep.dev](https://ssf.caep.dev) transmitter — no Keycloak setup,
no public tunnel, and no admin server of your own. POLL delivery means
caep.dev never has to reach back to your machine, so localhost is enough.

### 1. Register at caep.dev and grab an access token

Sign in at <https://ssf.caep.dev> and copy the access token from the
dashboard. caep.dev hands these out long-lived and out-of-band rather than
via an OAuth grant — they go in `Authorization: Bearer …` on every outbound
call the receiver makes.

### 2. Start a transmitter session at caep.dev

Open <https://caep.dev/transmitter/events>, set the **audience** to a value
of your choice (e.g. `https://my-receiver.example/ssf`), and start the
transmitter. Leave the tab open — you'll fire events from here in step 5.

### 3. Run the receiver-managed example in POLL mode

```sh
export SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN=<your-caep-dev-token>
export SSF_RECEIVER_EXPECTED_AUDIENCE=https://my-receiver.example/ssf

mvn -pl examples/example-receiver-managed-stream quarkus:dev \
    -Dquarkus.profile=caepdev \
    -Dssf.receiver.delivery-method=POLL \
    -Dssf.receiver.poll.interval=5s
```

The `caepdev` profile points the receiver at `https://ssf.caep.dev` and
selects the static-token outbound auth provider. The two `-D` overrides
flip the example from PUSH (its profile default) to POLL — no inbound
endpoint to expose. By default the receiver subscribes to
`session-revoked` and `credential-change`; edit
`ssf.receiver.events-requested` in `application.properties` to widen.

### 4. Confirm the receiver registered with caep.dev

```sh
curl -s localhost:28080/transmitter/registration | jq
# → the stream_id caep.dev assigned to this receiver
```

### 5. Trigger an event from caep.dev

In the transmitter tab from step 2, fire a `session-revoked` (or any
configured event type) for a subject of your choice.

### 6. See it arrive

```sh
curl -s localhost:28080/events/recent-events | jq
# → the most recent SET, with verified jti / iss / events / payload
```

That's the full receiver loop — discover-or-create stream, poll, verify
signature & claims, dispatch to your handler, ack — running against a
real transmitter in under a minute.

### Minimum configuration cheatsheet

For pointing at your own transmitter (Keycloak, on-prem IdP, custom),
either of these snippets is enough:

```properties
# Transmitter-managed (operator owns the stream)
ssf.receiver.transmitter-issuer=https://transmitter.example
ssf.receiver.stream-management=TRANSMITTER
ssf.receiver.stream-id=<from the transmitter's admin / onboarding flow>
ssf.receiver.delivery-method=PUSH
ssf.receiver.push.expected-auth-header=Bearer <shared-secret>   # optional
```

```properties
# Receiver-managed (extension creates / rediscovers the stream)
ssf.receiver.transmitter-issuer=https://transmitter.example
ssf.receiver.stream-management=RECEIVER
ssf.receiver.delivery-method=PUSH                               # or POLL
ssf.receiver.push.delivery-endpoint-url=https://my-app.example/ssf/push
ssf.receiver.expected-audience=https://my-app.example
# Built-in CAEP / RISC aliases work directly; full URIs are also accepted.
ssf.receiver.events-requested=CaepSessionRevoked,CaepCredentialChange
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

> "I'm a backend that authenticates users via an OIDC IdP. When the IdP
> revokes a session or rotates credentials, I need to drop my local sessions
> and tokens so the user can't continue with stale state."

```java
@ApplicationScoped
public class SessionRevocationHandler implements SsfEventHandler {

    @Inject SessionStore sessions;
    @Inject TokenCache tokens;

    @Override
    public void handle(SsfEventContext eventContext) {
        String subjectId = (String) eventContext.eventToken().subjectId().get("sub");
        if (subjectId == null) return;

        // CAEP aliases are built-in — no constants, no configuration needed.
        if (eventContext.hasEvent("CaepSessionRevoked")) {
            sessions.invalidateAllFor(subjectId);
        }
        if (eventContext.hasEvent("CaepCredentialChange")) {
            tokens.invalidateAllFor(subjectId);
            sessions.requireReauthFor(subjectId);
        }
    }
}
```

```properties
ssf.receiver.transmitter-issuer=https://transmitter.example
ssf.receiver.stream-management=TRANSMITTER
ssf.receiver.stream-id=<from the transmitter's admin / onboarding flow>
ssf.receiver.delivery-method=PUSH
ssf.receiver.expected-audience=https://my-app.example
ssf.receiver.push.expected-auth-header=Bearer ${PUSH_SHARED_SECRET}
```

**Key points:**
- TRANSMITTER-managed: simplest setup, operator creates the stream in the transmitter's admin console.
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
    @Inject ObjectMapper json;

    @Override
    public void handle(SsfEventContext eventContext) {
        // Forward the verified token + the alias-resolved issuer so downstream
        // consumers don't have to reproduce the alias lookup.
        SsfEventToken eventToken = eventContext.eventToken();
        Map<String, Object> payload = Map.of(
                "jti", eventToken.jti(),
                "iss", eventToken.iss(),
                "issAlias", eventContext.issAlias(),
                "iat", eventToken.iat().toString(),
                "events", eventToken.events(),
                "eventsByAlias", eventContext.eventsByAlias(),
                "subjectId", eventToken.subjectId(),
                "additionalProperties", eventToken.additionalProperties());
        try {
            producer.send(json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("could not serialise SSF event " + eventToken.jti(), e);
        }
    }
}
```

```properties
ssf.receiver.transmitter-issuer=https://transmitter.example
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

    @Inject SubjectFlags flags;

    @Override
    public void handle(SsfEventContext eventContext) {
        if (eventContext.hasEvent("CaepAssuranceLevelChange")
                || eventContext.hasEvent("CaepDeviceComplianceChange")) {
            String sub = (String) eventContext.eventToken().subjectId().get("sub");
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
| **Java (examples)** | 21 | 21 | Examples inherit the same Java 21 floor so they're copy-pasteable for consumers. |
| **Native image** | GraalVM 21 (Mandrel-equivalent) | — | The extension compiles native; the built-in CI workflow validates this on every PR for both example apps. |

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

Four-way selection, made at **build time**. The first match wins:

| Configured | Provider | Notes |
|---|---|---|
| `ssf.receiver.transmitter-access-token` set | `StaticTransmitterTokenProvider` | Sends the literal value as `Authorization: Bearer …`. For transmitters like caep.dev that issue long-lived tokens out-of-band. |
| `ssf.receiver.oauth2.token-endpoint` set | `Oauth2TransmitterTokenProvider` | Self-contained `client_credentials` grant — no `quarkus-oidc-client` dependency. Caches the token in-process with expiry-safety-window-aware refresh and concurrent-refresh coalescing. Supports `client_secret_basic` (default, RFC 6749 §2.3.1) and `client_secret_post` via `ssf.receiver.oauth2.client-auth-method`. |
| `quarkus-oidc-client` on the classpath | `OidcTransmitterTokenProvider` | Fetches a token via `quarkus.oidc-client.*` (typically `client_credentials`). Useful when the consumer already wires OIDC for inbound auth and wants to share the client config. |
| Neither | `NoopTransmitterTokenProvider` | No `Authorization` header. Fine for purely local stubs / public metadata. |

The deployment processor reads the activating properties under the active
profile and logs which provider it registered. Dev mode rebuilds on profile
change so this is seamless; for a packaged jar, repackage with the desired
profile.

```properties
# Self-contained OAuth2 example — no quarkus-oidc-client needed.
ssf.receiver.oauth2.token-endpoint=https://auth.example/oauth/token
ssf.receiver.oauth2.client-id=my-client
ssf.receiver.oauth2.client-secret=${OAUTH2_CLIENT_SECRET}
# Optional defaults shown:
#ssf.receiver.oauth2.grant-type=client_credentials
#ssf.receiver.oauth2.client-auth-method=basic       # or 'post'
#ssf.receiver.oauth2.scopes=ssf.read,ssf.manage
#ssf.receiver.oauth2.expiry-safety-window=30s
#ssf.receiver.oauth2.timeout=5s
```

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

`SsfAliases` keeps configuration and observability readable: instead of repeating
80-character event-type URIs across config, log lines, and Micrometer tags,
the extension swaps in a short alias name. It's a regular CDI bean, so
application code can `@Inject` it for the same naming outside the extension's
own usage.

Three independent alias domains:

```properties
# 1. Event-type URI → short alias. Built-ins cover SSF + CAEP + RISC out of
#    the box (see table below); the lines here are only for vendor-specific
#    URIs the spec doesn't cover.
ssf.receiver.event-aliases.VendorWidgetReplaced=https://schemas.example.org/vendor/event-type/widget-replaced

# 2. Transmitter issuer URL → short alias. No built-ins.
ssf.receiver.issuer-aliases.CaepDev=https://ssf.caep.dev
ssf.receiver.issuer-aliases.MyTransmitter=https://transmitter.example

# 3. This receiver's own short name — surfaces as the `receiver` tag on
#    Micrometer meters. Falls back to expected-audience, then "unknown".
ssf.receiver.alias=my-receiver
```

### Where aliases show up

| Surface | Behavior |
|---|---|
| **Handler code** (`SsfEventContext`) | `eventContext.hasEvent("CaepSessionRevoked")` and `eventContext.eventFor(...)` accept either an alias or a full URI. `eventContext.issAlias()` and `eventContext.eventsByAlias()` expose the resolved view directly. |
| **Config** (`ssf.receiver.events-requested`) | Each entry can be a URI or an alias. Unknown aliases fail fast at startup with a list of registered names. |
| **Metrics** (`ssf.receiver.events.processed`) | The `event`, `iss`, and `receiver` tags are alias values, so Prometheus / Grafana don't show URIs. |
| **Logs** | `LoggingSsfEventHandler` and the registrar / probe startup lines emit alias-resolved values. |

### Built-in event-type aliases

Covered out of the box — no config needed.

| Spec                                                                       | URI suffix (under `…/secevent/<spec>/event-type/`) | Aliases |
|----------------------------------------------------------------------------|---|---|
| **OpenID SSF 1.0 (Stream Events)**                                         | `verification`, `stream-updated` | `SsfStreamVerification`, `SsfStreamUpdated` |
| [**OpenID CAEP 1.0**](https://openid.net/specs/openid-caep-1_0-final.html) | `session-revoked`, `token-claims-change`, `credential-change`, `assurance-level-change`, `device-compliance-change`, `session-established`, `session-presented`, `risk-level-change` | `CaepSessionRevoked`, `CaepTokenClaimsChange`, `CaepCredentialChange`, `CaepAssuranceLevelChange`, `CaepDeviceComplianceChange`, `CaepSessionEstablished`, `CaepSessionPresented`, `CaepRiskLevelChange` |
| [**OpenID RISC 1.0**](https://openid.net/specs/openid-risc-1_0-final.html) | `account-credential-change-required`, `account-purged`, `account-disabled`, `account-enabled`, `identifier-changed`, `identifier-recycled`, `credential-compromise`, `opt-in`, `opt-out-initiated`, `opt-out-cancelled`, `opt-out-effective`, `recovery-activated`, `recovery-information-changed` | `RiscAccountCredentialChangeRequired`, `RiscAccountPurged`, `RiscAccountDisabled`, `RiscAccountEnabled`, `RiscIdentifierChanged`, `RiscIdentifierRecycled`, `RiscCredentialCompromise`, `RiscOptIn`, `RiscOptOutInitiated`, `RiscOptOutCancelled`, `RiscOptOutEffective`, `RiscRecoveryActivated`, `RiscRecoveryInformationChanged` |

Consumer entries in `ssf.receiver.event-aliases.*` **overlay** the built-ins —
a user mapping for a built-in URI replaces the alias name (the URI itself
stays the same).

### Bean API

```java
@Inject SsfAliases aliases;

aliases.eventTypeAlias(uri);    // URI  → alias (or URI passthrough if unknown)
aliases.issuerAlias(issUrl);    // URL  → alias (or URL passthrough if unknown)
aliases.receiverAlias();        // this receiver's short name; never empty

aliases.resolveEventTypeRef("CaepSessionRevoked"); // alias  → URI
aliases.resolveEventTypeRef("https://…");          // URI    → URI (passthrough)
// Unknown alias → IllegalArgumentException listing the registered set.

aliases.eventTypeAliasesByUri();  // read-only view (Dev UI uses this)
aliases.issuerAliasesByUri();
```

Unknown URIs fall through unchanged from the lookup methods — nothing is
ever lost. Only `resolveEventTypeRef` throws, since its caller (the config
validator) needs to surface typos eagerly.

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
mvn -pl runtime,deployment test          # full layer-1 + layer-2 test suite

# Run the examples — see each example's README for the env vars they need.
mvn -pl examples/example-transmitter-managed-stream quarkus:dev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev -Dquarkus.profile=caepdev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev -Dquarkus.profile=keycloak
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
