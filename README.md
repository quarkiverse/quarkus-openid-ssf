# quarkus-openid-ssf

[![Build (JVM)](https://github.com/quarkiverse/quarkus-openid-ssf/actions/workflows/build.yml/badge.svg)](.github/workflows/build.yml)
[![Native build](https://github.com/quarkiverse/quarkus-openid-ssf/actions/workflows/native.yml/badge.svg)](.github/workflows/native.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Quarkus extension that lets a Quarkus app act as a [Shared Signals Framework
(SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
receiver against any spec-compliant SSF transmitter: public providers like
[caep.dev](https://ssf.caep.dev), on-prem / self-hosted IdPs such as Keycloak, or
custom implementations.

The protocol work is done by [easyssf](https://github.com/easyssf/easyssf), a
framework independent SSF receiver library that is shared with its Spring Boot
starter: SET verification, metadata discovery, stream management, PUSH and POLL
delivery, de-duplication and the OpenID conformance suite results. The extension
adds what is Quarkus: configuration, CDI wiring, the Vert.x push route, the poll
scheduler, the token providers, Micrometer, a health check, JDBC, the Dev UI and
native image support.

| | |
|---|---|
| **Status** | Experimental, APIs may change before 1.0. |
| **License** | [Apache-2.0](LICENSE) |
| **Java** | 21+ (extension and examples both compile under `--release 21`) |
| **Quarkus** | 3.35.x (floor, see [Compatibility](#compatibility)) |
| **Group ID** | `io.quarkiverse.openid-ssf` |
| **Receiver artifact** | `quarkus-openid-ssf-receiver` |

## What it does

- Accepts inbound SETs via **PUSH** (RFC 8935): a Vert.x route at `/ssf/push`,
  answering with the RFC 8935 error documents.
- Pulls SETs via **POLL** (RFC 8936): a periodic Vert.x timer per transmitter,
  acknowledgements, error reports, `Retry-After` handling, a manual `pollNow()`.
- Verifies every SET: JWS signature against the transmitter's JWK Set, `typ`
  header, `iss` / `iat` / `jti` / `aud` / `events` per RFC 8417. RS256-only with
  a 2048-bit minimum RSA key by default (CAEP Interop Profile), both tunable.
- Manages the stream of the receiver: **`RECEIVER`** (default) looks the stream
  up at the transmitter on startup and creates or updates it, with retries in
  the background; **`TRANSMITTER`** uses a stream an operator created.
- Validates stream verification events against the state the receiver sent.
- Receives from **several transmitters**, each with its own settings, selected
  by the issuer of a SET.
- Exposes the stream management API (SSF section 8.1): configuration, status,
  subjects, verification.
- Optional: Micrometer metrics, a SmallRye Health wellness check, a JDBC
  de-duplication store over the default Agroal datasource, a Dev UI.
- Outbound auth: static bearer token, OAuth2 `client_credentials` (no extra
  dependency), `quarkus-oidc-client`, or none.

## Layout

| Module | Role |
|---|---|
| [`receiver/runtime/`](receiver/runtime/) | Extension runtime: configuration, the producers that wire easyssf into CDI, push route, poll scheduler, token providers, metrics, health, JDBC, Dev UI service. |
| [`receiver/deployment/`](receiver/deployment/) | Build-time processor: registers the beans, wires the optional integrations when their extension is present. Contains the tests, which run against easyssf's in-process `TestTransmitter`. |
| [`receiver/examples/`](receiver/examples/) | Four runnable applications and a Keycloak setup, see the [examples README](receiver/examples/README.md). |

## Consumer SPI

Provide a CDI bean implementing easyssf's `SsfEventHandler`. Every handler bean is
invoked for every verified SET, in `@Priority` order:

```java
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;

@ApplicationScoped
public class MyHandler implements SsfEventHandler {
    @Override
    public void handle(SsfEventContext eventContext) {
        SsfEventToken eventToken = eventContext.eventToken();
        // RFC 8417 + SSF profile fields: jti(), iss(), iat(), aud(), events(), subjectId(), txn(), claims()

        // Aliases and URIs are interchangeable; the SSF, CAEP and RISC event types
        // have built-in aliases (CaepSessionRevoked, RiscAccountDisabled, ...).
        if (eventContext.hasEvent("CaepSessionRevoked")) {
            SsfSubject subject = eventContext.subjectFor("CaepSessionRevoked");
            // subject.subject(), subject.sessionId(), subject.email(), ...
        }
        Map<String, Object> payload = eventContext.eventFor("CaepCredentialChange");
    }
}
```

The handler runs **before** the transmitter gets its answer: a handler that throws
makes the push endpoint answer `500` and the poller leave the SET unacknowledged,
so the transmitter delivers it again. Handlers therefore have to be idempotent.
Without a handler bean, `LoggingSsfEventHandler` logs every SET at INFO.

Other beans of the extension and of easyssf that applications use:

| Bean | What for |
|---|---|
| `SsfTransmitters` | Every configured transmitter (`all()`, `get(name)`, `byIssuer(issuer)`, `primary()`), each an `SsfTransmitter` with its metadata resolver, verifier, stream client, stream, registrar and poller. |
| `SsfReceiverStreamClient` | The stream of the receiver at the default transmitter without passing the stream id around: `configuration()`, `status()`, `updateStatus()`, `addSubject()`, `removeSubject()`, `requestVerification()`, `deleteStream()`. |
| `SsfPollScheduler` | `pollNow()` / `pollNow(name)` to poll a transmitter once, for `poll.auto-start=false`. |
| `SsfTransmitterCustomizer` | A bean of this type customizes the `SsfTransmitter.Builder` of every transmitter before it is built: replace the verifier, the token provider, tune the poller. |

Every producer of the extension is a `@DefaultBean`: an application bean of the
same type replaces it, for example an `SsfHttpClient`, an `SsfJtiDedupStore`, an
`SsfReceiverMetrics` or the whole `SsfTransmitters`.

## Quick start

The fastest way to see the extension light up end-to-end is against the
public [caep.dev](https://ssf.caep.dev) transmitter: no Keycloak setup, no
public tunnel. POLL delivery means caep.dev never has to reach your machine.

1. Sign in at <https://ssf.caep.dev> and copy the access token.
2. Open <https://caep.dev/transmitter/events>, set the **audience** to a value of
   your choice (for example `https://my-receiver.example/ssf`) and start the
   transmitter.
3. Run the receiver-managed example in POLL mode:

   ```sh
   export SSF_RECEIVER_TRANSMITTER_ACCESS_TOKEN=<your-caep-dev-token>
   export SSF_RECEIVER_EXPECTED_AUDIENCE=https://my-receiver.example/ssf

   mvn -pl receiver/examples/example-receiver-managed-stream quarkus:dev \
       -Dquarkus.profile=caepdev,poll \
       -Dquarkus.openid-ssf.receiver.poll.interval=5s
   ```

4. `curl -s localhost:28080/transmitter/registration | jq` shows the stream the
   receiver registered.
5. Fire a `session-revoked` event in the transmitter tab.
6. `curl -s localhost:28080/events/recent-events | jq` shows it, verified and
   handled.

For a complete setup with Keycloak as identity provider and transmitter, with a
resource server that rejects the tokens of revoked sessions and a web
application that ends revoked sessions, see the [examples](receiver/examples/README.md).

### Minimum configuration

```properties
# Receiver-managed (the default): the extension looks the stream up and creates or updates it
quarkus.openid-ssf.receiver.transmitter-issuer=https://transmitter.example
quarkus.openid-ssf.receiver.delivery-method=PUSH                               # or POLL
quarkus.openid-ssf.receiver.push.delivery-endpoint-url=https://my-app.example/ssf/push
quarkus.openid-ssf.receiver.push.expected-auth-header=Bearer ${PUSH_SHARED_SECRET}
# Built-in CAEP / RISC aliases work directly; full URIs are also accepted.
quarkus.openid-ssf.receiver.events-requested=CaepSessionRevoked,CaepCredentialChange
# How the receiver authenticates at the stream management (and poll) endpoints
quarkus.openid-ssf.receiver.oauth2.token-endpoint=https://transmitter.example/oauth/token
quarkus.openid-ssf.receiver.oauth2.client-id=my-receiver
quarkus.openid-ssf.receiver.oauth2.client-secret=${CLIENT_SECRET}
```

```properties
# Transmitter-managed: an operator created the stream, the receiver looks it up
quarkus.openid-ssf.receiver.transmitter-issuer=https://transmitter.example
quarkus.openid-ssf.receiver.stream-management=TRANSMITTER
quarkus.openid-ssf.receiver.stream-id=<from the transmitter's admin console>
quarkus.openid-ssf.receiver.push.expected-auth-header=Bearer ${PUSH_SHARED_SECRET}
quarkus.openid-ssf.receiver.transmitter-access-token=${TRANSMITTER_TOKEN}
```

The audience of inbound SETs is taken from the stream the receiver looked up or
registered; set `expected-audience` to check a fixed one instead. A transmitter
issuer must use `https`, or `http` on `localhost`; `allow-insecure-http=true` lifts
that for test setups.

### Several transmitters

The settings at `quarkus.openid-ssf.receiver.*` configure the transmitter named
`default`. `quarkus.openid-ssf.receiver.<name>.*` configures another transmitter
with the same settings, each complete on its own; the issuer of a SET selects the
transmitter that verifies it, and each has its own push authorization header,
stream and poller:

```properties
quarkus.openid-ssf.receiver.transmitter-issuer=https://transmitter.example
quarkus.openid-ssf.receiver.push.expected-auth-header=Bearer ${PUSH_SECRET}

quarkus.openid-ssf.receiver.keycloak.transmitter-issuer=https://id.example/realms/prod
quarkus.openid-ssf.receiver.keycloak.push.expected-auth-header=Bearer ${KEYCLOAK_PUSH_SECRET}
quarkus.openid-ssf.receiver.keycloak.oauth2.token-endpoint=https://id.example/realms/prod/protocol/openid-connect/token
quarkus.openid-ssf.receiver.keycloak.oauth2.client-id=receiver
quarkus.openid-ssf.receiver.keycloak.oauth2.client-secret=${KEYCLOAK_SECRET}
```

The name of the transmitter is the `transmitter` tag of the metrics. The push
endpoint, HTTP client, de-duplication and event aliases are shared by all
transmitters.

## Stream management

With `stream-management=RECEIVER` the stream registrar of easyssf runs on a
background virtual thread after startup: it lists the streams of the receiver
at the transmitter, reuses one with the same delivery, updates one whose events
differ, or creates one. The transmitter being down does not keep the application
from starting; the registrar retries with exponential backoff (1s to 30s) and
the push endpoint accepts SETs in the meantime. With a `stream-id` the stream is
looked up the same way, to learn its audience and poll endpoint.

```
INFO  Created SSF stream d0200637-… (delivery urn:ietf:rfc:8935 https://my-app.example/ssf/push, events [CaepSessionRevoked], audience [my-receiver/d0200637-…])
```

`receiver-managed.delete-on-shutdown=true` deletes the stream when the
application stops (dev mode, tests). The health check and the Dev UI show the
state of the registration.

## jti deduplication

A SET that was processed before is skipped after verification and before the
handlers: PUSH still answers `202`, POLL still acknowledges it. The key is
`iss` + `jti`, so transmitters with colliding identifiers stay apart.

```properties
quarkus.openid-ssf.receiver.dedup.enabled=true       # false if the handlers are idempotent anyway
quarkus.openid-ssf.receiver.dedup.capacity=10000     # entries of the in-memory store
```

With `quarkus-agroal` and a default datasource, processed SETs are remembered in
the table `EASYSSF_PROCESSED_SET` instead, so that all instances of the
application share them and they survive a restart:

```properties
quarkus.openid-ssf.receiver.jdbc.enabled=true              # the default when Agroal is present
quarkus.openid-ssf.receiver.jdbc.initialize-schema=true    # create the table on startup; false: schema.sql of easyssf-receiver-jdbc
quarkus.openid-ssf.receiver.jdbc.table-prefix=EASYSSF_
quarkus.openid-ssf.receiver.jdbc.cleanup-interval=15m
quarkus.openid-ssf.receiver.dedup.retention=7d
```

An `SsfJtiDedupStore` bean of the application (Redis, ...) replaces both.

## Delivery: PUSH

The push endpoint defaults to `POST /ssf/push`, relative to
`quarkus.http.root-path`; override with `quarkus.openid-ssf.receiver.push.endpoint-path`.
A SET is verified and handled before the response:

| Condition | Response |
|---|---|
| `push.expected-auth-header` of the transmitter is set and the `Authorization` header differs | `401 {"err":"authentication_failed"}` |
| Larger than 256 KiB | `413 {"err":"invalid_request"}` |
| Not a signed JWT, no `typ`, missing claims, wrong issuer, audience, key, algorithm | `400 {"err":"invalid_request" / "invalid_issuer" / "invalid_audience" / "invalid_key"}` |
| The keys of the transmitter cannot be retrieved | `503`, the transmitter delivers the SET again |
| A handler threw | `500`, the transmitter delivers the SET again |
| Verified and handled (or a duplicate) | `202` |

The JWK Set is fetched on the first SET, cached and fetched again when a SET is
signed with an unknown key.

With `quarkus-oidc` in the same application, switch proactive authentication off
(`quarkus.http.auth.proactive=false`): otherwise the `Authorization: Bearer …`
header the transmitter sends to the push endpoint is taken for an access token
and the SET is rejected with `401` before it reaches the receiver. The push
endpoint needs no login, the permission policies of the application decide what
does.

## Delivery: POLL (RFC 8936)

```properties
quarkus.openid-ssf.receiver.delivery-method=POLL

# Optional, defaults shown.
quarkus.openid-ssf.receiver.poll.interval=30s
quarkus.openid-ssf.receiver.poll.start-delay=0s             # delay before the first poll
quarkus.openid-ssf.receiver.poll.auto-start=true            # false: poll with SsfPollScheduler.pollNow()
quarkus.openid-ssf.receiver.poll.max-events=100             # per request; a poll fetches again while moreAvailable
quarkus.openid-ssf.receiver.poll.rate-limit.max-backoff=5m  # cap on any pause
#quarkus.openid-ssf.receiver.poll.rate-limit.fallback-backoff=30s   # pause after a 429 without Retry-After
# Override only if the poll endpoint is not in the stream configuration:
#quarkus.openid-ssf.receiver.poll.endpoint-url=https://transmitter.example/ssf/poll/<stream>
```

The poll endpoint is taken from the `delivery.endpoint_url` of the stream the
receiver looked up or registered. A handled SET is acknowledged right away (not
with the next poll), an invalid one is reported in `setErrs`, one a handler
could not process is left for the next poll. On `401` the token provider is
asked for a new token once. A `429` (or `503` with `Retry-After`) pauses
polling for `Retry-After`, capped by `max-backoff`; a `429` without the header
pauses for `fallback-backoff` if set.

Timeouts of all calls to the transmitters are those of the HTTP client:
`quarkus.openid-ssf.receiver.http.connect-timeout` and `read-timeout` (5s each),
plus an optional `http.user-agent`.

## Outbound auth to the transmitter

Chosen at runtime, per transmitter; the first match wins:

| Configured | Provider |
|---|---|
| `transmitter-access-token` | A fixed `Authorization: Bearer …`, for transmitters like caep.dev that issue long-lived tokens out-of-band. |
| `oauth2.token-endpoint` | easyssf's `ClientCredentialsSsfTransmitterTokenProvider`: `client_credentials` grant, no `quarkus-oidc-client` needed. Caches the token with `oauth2.expiry-safety-window`, `client_secret_basic` (default) or `client_secret_post` via `oauth2.client-auth-method`, `oauth2.scopes`, `oauth2.additional-params`. |
| `quarkus-oidc-client` on the classpath | `OidcTransmitterTokenProvider`: tokens from the OIDC client configured with `quarkus.oidc-client.*` (`oidc.client-name` picks a named one, `oidc.token-timeout` bounds the wait). |
| Neither | No `Authorization` header. Fine for PUSH with a public JWK Set. |

The log tells which one was chosen for each transmitter. Metadata and JWK Set
are fetched without a token.

## Metrics (optional)

With `quarkus-micrometer` (or a registry extension) the receiver records to the
`MeterRegistry`, scraped at `/q/metrics`:

| Meter | Type | Tags |
|---|---|---|
| `easyssf.receiver.sets` | counter | `transmitter` (the name), `delivery` ∈ {`push`, `poll`}, `outcome` ∈ {`handled`, `duplicate`, `invalid`, `unauthenticated`, `unavailable`, `failed`} |
| `easyssf.receiver.events` | counter | `transmitter`, `delivery`, `event` (the alias of the event type) |
| `easyssf.receiver.poll` | timer | `transmitter`, `outcome` ∈ {`success`, `failure`} |
| `easyssf.receiver.dedup.size` | gauge | the identifiers the in-memory store remembers |

The names are those of easyssf, so a Spring Boot and a Quarkus receiver share
one dashboard.

## Health (optional)

With `quarkus-smallrye-health` the receiver reports a wellness check at
`/q/health/well` (it does not gate readiness, a transmitter that is down must not
take the application out of service): per transmitter whether the metadata was
retrieved, the state of the stream registration and, with POLL, the last poll and
its error. The check is `DOWN` when a stream cannot be used or the last poll
failed.

## Event type aliases

Wherever an event type is named (`events-requested`, handlers, metric tags, logs)
an alias can stand for the URI. The SSF, CAEP and RISC event types have built-in
aliases:

| Spec | URI suffix (under `…/secevent/<spec>/event-type/`) | Aliases |
|---|---|---|
| **OpenID SSF 1.0** | `verification`, `stream-updated` | `SsfStreamVerification`, `SsfStreamUpdated` |
| [**OpenID CAEP 1.0**](https://openid.net/specs/openid-caep-1_0-final.html) | `session-revoked`, `token-claims-change`, `credential-change`, `assurance-level-change`, `device-compliance-change`, `session-established`, `session-presented`, `risk-level-change` | `CaepSessionRevoked`, `CaepTokenClaimsChange`, `CaepCredentialChange`, `CaepAssuranceLevelChange`, `CaepDeviceComplianceChange`, `CaepSessionEstablished`, `CaepSessionPresented`, `CaepRiskLevelChange` |
| [**OpenID RISC 1.0**](https://openid.net/specs/openid-risc-1_0-final.html) | `account-credential-change-required`, `account-purged`, `account-disabled`, `account-enabled`, `identifier-changed`, `identifier-recycled`, `credential-compromise`, `opt-in`, `opt-out-initiated`, `opt-out-cancelled`, `opt-out-effective`, `recovery-activated`, `recovery-information-changed` | `RiscAccountCredentialChangeRequired`, `RiscAccountPurged`, `RiscAccountDisabled`, `RiscAccountEnabled`, `RiscIdentifierChanged`, `RiscIdentifierRecycled`, `RiscCredentialCompromise`, `RiscOptIn`, `RiscOptOutInitiated`, `RiscOptOutCancelled`, `RiscOptOutEffective`, `RiscRecoveryActivated`, `RiscRecoveryInformationChanged` |

Vendor specific event types get their aliases from configuration; an alias cannot
redefine a built-in one, a conflict fails the start:

```properties
quarkus.openid-ssf.receiver.event-aliases.VendorWidgetReplaced=https://schemas.example.org/vendor/event-type/widget-replaced
```

`SsfEventTypes.resolve(aliasOrUri)` and `SsfEventTypes.aliasOf(uri)` do the
lookups in application code.

## Disable switch

```properties
quarkus.openid-ssf.receiver.enabled=false
```

When `false`, no push endpoint is registered, no stream is looked up or
registered and nothing is polled; the application starts without a transmitter
configured. Useful for `%test`, `%dev`-without-transmitter or a runtime
kill-switch via env var.

## Testing an application

`org.easyssf:easyssf-test` has `TestTransmitter`, an in-process SSF transmitter
and identity provider: it publishes metadata and a JWK Set, signs SETs and access
tokens, is a client credentials token endpoint, and emulates the stream API and
the poll endpoint. The tests of the extension and of the examples use it instead
of a mock server; see `ResourceServerTest` in the resource server example for a
`QuarkusTestResourceLifecycleManager` that points the receiver (and
`quarkus-oidc`) at it.

## Compatibility

| | Tested | Floor | Notes |
|---|---|---|---|
| **Quarkus** | 3.35.x | 3.35.0 | Earlier versions may work but aren't tested. CI matrix in `.github/workflows/build.yml` is the source of truth. |
| **Java (extension)** | 21, 25 | 21 | The runtime + deployment artifacts compile under `--release 21`. Consumers may run on any 21+. |
| **Java (examples)** | 21 | 21 | Examples inherit the same Java 21 floor so they're copy-pasteable for consumers. |
| **Native image** | GraalVM 21 (Mandrel-equivalent) | — | The native CI workflow builds the examples and runs the integration tests of the resource server and the receiver-managed example against the native binaries. |

## Build

```sh
mvn -DskipTests install                                                # build + install all artifacts
mvn -pl receiver/runtime,receiver/deployment install                   # the test suite, against easyssf's TestTransmitter

# Run the examples, see receiver/examples/README.md.
mvn -pl receiver/examples/example-resource-server quarkus:dev
mvn -pl receiver/examples/example-resource-server quarkus:dev -Dquarkus.profile=poll
mvn -pl receiver/examples/example-oidc-client     quarkus:dev
```

The extension depends on a released `org.easyssf:easyssf-receiver`; a port
against a snapshot of easyssf needs `./mvnw install -DskipTests` in the easyssf
checkout first.

## Migrating from 0.1.x

See the [changelog](CHANGELOG.md) for the breaking changes of 0.2.0: package names
of the SPI, removed configuration properties, metric names and the push
semantics.

## Out of scope (today)

- A durable store of pending poll acknowledgements: acknowledgements are sent
  right after a SET was handled, a SET that was not acknowledged is delivered
  again by the transmitter.
- Per-event-type CAEP / RISC parsing beyond the subject: `SsfEventContext`
  exposes the raw event payloads, consumers parse what they care about.
