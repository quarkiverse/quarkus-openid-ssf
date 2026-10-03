# Changelog

## 0.2.0 (unreleased)

The receiver is built on [easyssf](https://github.com/easyssf/easyssf)
(`org.easyssf:easyssf-receiver`), the SSF receiver library shared with its Spring
Boot starter. The protocol code of the extension (verification, metadata, stream
management, push, poll, de-duplication, OAuth2 client credentials) is replaced by
easyssf; the extension keeps configuration, CDI wiring, the Vert.x push route,
the poll scheduler, the token providers, Micrometer, the Dev UI and the
build-time processor.

### New

- Several transmitters: `quarkus.openid-ssf.receiver.<name>.*` configures a further
  transmitter next to the default one, with its own stream, push authorization
  header, token provider and poller. `SsfTransmitters` gives them all.
- `quarkus.openid-ssf.receiver.allow-insecure-http`: the transmitter issuer must use
  `https`, or `http` on loopback addresses; this lifts the rule for test setups.
- `set-validation.require-type-header` (SETs must be typed `secevent+jwt`) and
  `set-validation.clock-skew`.
- `http.connect-timeout`, `http.read-timeout` and `http.user-agent` for all calls
  to the transmitters.
- A JDBC de-duplication store over the default Agroal datasource (`jdbc.*`,
  `dedup.retention`), so that all instances of an application share the
  processed SETs.
- A SmallRye Health wellness check (`/q/health/well`) with `quarkus-smallrye-health`.
- Stream verification events are validated against the state the receiver sent.
- The registrar updates a stream whose requested events changed, and handles a
  transmitter that allows a single stream per receiver.
- `SsfTransmitterCustomizer` beans customize every transmitter before it is built.
- `oidc.client-name` picks a named `quarkus.oidc-client.<name>` for a transmitter.
- Tests of applications can use `org.easyssf:easyssf-test` (`TestTransmitter`).
- `receiver/conformance`: a receiver under test for the OpenID conformance suite
  and tests that run the suite's four SSF receiver plans against it, with
  `org.easyssf:easyssf-test-conformance` (Docker; `mvn -Pconformance verify`).
- Two more examples, modelled on the easyssf Spring Boot examples: a resource
  server that rejects the access tokens of revoked sessions and a web application
  that ends revoked sessions, with a Keycloak Docker Compose setup.

### Migrating to 0.2.0

**Event handler SPI.** `SsfEventHandler`, `SsfEventContext` and `SsfEventToken` are
the types of easyssf: `org.easyssf.receiver.event.SsfEventHandler`,
`org.easyssf.receiver.event.SsfEventContext`, `org.easyssf.core.event.SsfEventToken`.
`SsfEventContext.issAlias()`, `eventTypeAlias()` and `eventsByAlias()` are gone:
use `SsfEventTypes.aliasOf(uri)` and `SsfTransmitters.nameOf(issuer)`.
`SsfEventToken.additionalProperties()` is `claims()` (all claims).
`eventContext.subject()` / `subjectFor(eventType)` give a typed `SsfSubject`.

**Push semantics.** Handlers run before the response. A handler that throws yields
`500` and the transmitter delivers the SET again (RFC 8935); before, the SET was
acknowledged with `202` and the failure only logged. Error responses carry the
RFC 8935 documents (`{"err":"authentication_failed"}`, `{"err":"invalid_request"}`,
...). Handlers have to be idempotent.

**Poll semantics.** A handled SET is acknowledged right away instead of with the
next poll; an invalid SET is reported in `setErrs`. `SsfPoller` and the
`SsfPollAckStore` SPI are gone, polling is driven by `SsfPollScheduler.pollNow()`.

**Stream client.** `SsfStreamClient` of the extension is replaced by
`SsfReceiverStreamClient` (the stream of the default transmitter: `configuration()`,
`status()`, `updateStatus(String, String)`, `addSubject(Map, boolean)`,
`removeSubject(Map)`, `requestVerification()`, `listStreams()`, `deleteStream()`)
and easyssf's `SsfStreamClient` on every `SsfTransmitter` for arbitrary streams.
`StreamConfiguration`, `StreamStatus` and the DTOs are replaced by the
`SsfStreamConfiguration` and `SsfStreamStatus` records of easyssf (map based,
`claims()` is the JSON of the transmitter). `ReceiverManagedStreamState` is
`SsfTransmitter.getReceiverStream()`.

**Token providers.** The choice is made at runtime instead of at build time, per
transmitter, in the same order: static token, `oauth2.token-endpoint`,
`quarkus-oidc-client`, none. `TransmitterTokenProvider` is replaced by easyssf's
`SsfTransmitterTokenProvider`; `Oauth2TransmitterTokenProvider` by
`ClientCredentialsSsfTransmitterTokenProvider`.

**Metrics.** `ssf.receiver.*` is replaced by the meters of easyssf:
`easyssf.receiver.sets` (tags `transmitter`, `delivery`, `outcome`),
`easyssf.receiver.events` (`transmitter`, `delivery`, `event`),
`easyssf.receiver.poll` (`transmitter`, `outcome`) and `easyssf.receiver.dedup.size`.
The `iss` tag is now `transmitter`, the name of the transmitter; the `receiver`
tag is gone.

**Removed configuration properties.**

| Property | Replacement |
|---|---|
| `poll.return-immediately` | none, easyssf never long-polls |
| `poll.drain-on-poll` | none, a poll always fetches while `moreAvailable` |
| `poll.timeout` | `http.read-timeout` |
| `receiver-managed.register-stream` | `stream-management=TRANSMITTER` without a `stream-id` |
| `transmitter-managed.probe-on-startup` | none, a configured `stream-id` is always looked up (with retries) |
| `oauth2.grant-type` | none, an `SsfTransmitterTokenProvider` bean for other grants |
| `oauth2.timeout` | `http.connect-timeout` / `http.read-timeout` |
| `issuer-aliases.*` | the name of the transmitter (`quarkus.openid-ssf.receiver.<name>.*`) |
| `alias` | none |

`transmitter-issuer` is no longer required when `enabled=false`. The `stream-id`
is no longer required with `stream-management=TRANSMITTER`: without it, the
receiver does not call the stream management API on startup.

**Dependencies.** `quarkus-rest-client-jackson` is no longer pulled in; the
transmitters are called with the JDK HTTP client. `nimbus-jose-jwt` and
`slf4j-api` come with easyssf.

**Tests.** `JwksWireMock` and `TokenEndpointWireMock` are replaced by easyssf's
`TestTransmitter`; WireMock is no longer a test dependency.

## 0.1.0

The first release.
