# Changelog

## 0.5.0 (unreleased)

## 0.4.0

POLL delivery that survives restarts and runs on several instances, through
easyssf 0.3.0: the acknowledgements a poller owes its transmitter wait in a
store, in the database with a datasource; long polling keeps one request
outstanding; a SET is claimed while its handlers run so that two instances never
acknowledge a SET one of them failed on. The JDBC schema gained a column and a
table, with migration scripts and startup upgrades. Nothing changes for an
application that delivers by PUSH without a datasource.

### New

- `SsfPollAckStore`: a handled SET is acknowledged with the request that follows,
  and the acknowledgement waits in a store until a request carried it. In
  memory by default; with `quarkus-agroal` and a datasource in the table
  `EASYSSF_POLL_ACK` (`jdbc.ack-retention`, `jdbc.ack-delete-batch-size`), so a
  SET handled right before a restart is acknowledged afterwards instead of being
  delivered again. On shutdown the poller sends the pending acknowledgements
  with a last request. An `SsfPollAckStore` bean of the application replaces the
  built-in stores.
- Long polling (RFC 8936, section 2.5): `poll.long-polling=true` keeps one
  request outstanding that the transmitter holds for up to
  `poll.long-polling-hold` (30s); a SET arrives at once instead of at the next
  `poll.interval`. The request timeout of a long poll replaces
  `http.read-timeout`. Both names are those of the Spring Boot starter.
- `dedup.lease` (60s): how long a SET stays claimed while its handlers run. A
  SET another instance is handling is left to it (PUSH answers `500`, POLL does
  not acknowledge, metrics outcome `in_progress`); a claim older than the lease
  counts as abandoned by a crashed instance.
- Schema upgrades: with `jdbc.initialize-schema=true` a `PROCESSED_SET` table of
  an earlier release gets the `STATE` column on startup; with `false` the start
  fails naming the `ALTER TABLE` and the migration scripts of
  `easyssf-receiver-jdbc` (`classpath:org/easyssf/receiver/jdbc/migration/`,
  Flyway naming, plain SQL), which `quarkus.flyway.locations` can list.
- The gauge `easyssf.receiver.poll.pending-acks` (tag `transmitter`), the health
  details `polling` (`periodic`, `long`, `manual`) and `pendingAcks`, and a
  "Polling" section of the Dev UI stream page (`pollStatus()`): mode, last poll,
  error, pause, pending acknowledgements per transmitter.
- Typed CAEP and RISC events from easyssf 0.3.0: `SsfCaepEventHandler` and
  `SsfRiscEventHandler` dispatch the events of a SET to a method per event type,
  `SsfEventContext.idempotencyKey()` is the key for the side effects of a
  handler.

### Changed

- easyssf 0.3.0.
- The poller of a transmitter runs on a virtual thread of its own named
  `ssf-poller-<name>` (easyssf's loop, started and stopped by
  `SsfPollScheduler`) instead of a Vert.x periodic timer. `poll.auto-start=false`
  and `SsfPollScheduler.pollNow()` work as before. The pollers are stopped ahead
  of the stream registrars on shutdown.
- The table `EASYSSF_PROCESSED_SET` gained the column `STATE VARCHAR(16) NOT NULL`
  (`'PROCESSED'` or `'IN_PROGRESS'`); existing tables need
  `ALTER TABLE EASYSSF_PROCESSED_SET ADD STATE VARCHAR(16) DEFAULT 'PROCESSED' NOT NULL`
  (the migration script `V0_3_0__dedup_state_and_poll_acks.sql`), applied on
  startup with `jdbc.initialize-schema=true`. The schema has a third table,
  `EASYSSF_POLL_ACK`, created on startup the same way; `schema.sql` of
  `easyssf-receiver-jdbc` is the current schema for a fresh installation.
- `jdbc.cleanup-interval` purges the acknowledgement table as well.
- `SsfTransmitterFactory.poller(...)` and `builder(...)` take the
  `SsfPollAckStore`.
- No `synchronized` in the extension: locks are `ReentrantLock`, as the
  extension runs on virtual threads.

## 0.3.0

SCIM Events (RFC 9967) through easyssf 0.2.0: a SET that reports a change of a
SCIM resource is verified like any other, its event types have aliases, and
`SsfScimEventHandler` hands the events to a method per operation. A new example
mirrors SCIM users into a local directory. Nothing changes for existing
applications.

### New

- SCIM Events (RFC 9967) via easyssf 0.2.0: the event types under
  `urn:ietf:params:scim:event:` with the built-in aliases `ScimFeedAdd`,
  `ScimFeedRemove`, `ScimProvCreateNotice`, `ScimProvCreateFull`,
  `ScimProvPatchNotice`, `ScimProvPatchFull`, `ScimProvPutNotice`, `ScimProvPutFull`,
  `ScimProvDelete`, `ScimProvActivate`, `ScimProvDeactivate` and
  `ScimMiscAsyncResponse`, the `scim` subject identifier, `SsfScimEvent` for the
  typed payload and `SsfScimEventHandler`, which dispatches the SCIM Events of a
  SET to a method per operation. Nothing has to be configured.
- `example-scim-provisioning`: mirrors SCIM `Users` into a local directory with
  `SsfScimEventHandler`, driven by a demo SCIM service provider over easyssf's
  `TestTransmitter`, because Keycloak does not emit SCIM Events.

### Changed

- easyssf 0.2.0.

## 0.2.0

The receiver is built on [easyssf](https://github.com/easyssf/easyssf)
(`org.easyssf:easyssf-receiver`), the SSF receiver library shared with its Spring
Boot starter. The protocol code of the extension (verification, metadata, stream
management, push, poll, de-duplication, OAuth2 client credentials) is replaced by
easyssf; the extension keeps configuration, CDI wiring, the Vert.x push route,
the poll scheduler, the token providers, Micrometer, the Dev UI and the
build-time processor.

### New

- Built and tested against Quarkus 3.27.x (LTS); the floor moves from 3.35 to 3.27.
- Several transmitters: `quarkus.openid-ssf.receiver.<name>.*` configures a further
  transmitter next to the default one, with its own stream, push authorization
  header, token provider and poller. `SsfTransmitters` gives them all.
- `quarkus.openid-ssf.receiver.allow-insecure-http`: the transmitter issuer must use
  `https`, or `http` on loopback addresses; this lifts the rule for test setups.
- `set-validation.require-type-header` (SETs must be typed `secevent+jwt`) and
  `set-validation.clock-skew`.
- `set-validation.subject-compatibility`: `strict-ssf-1-0` (the default) requires the
  top-level `sub_id` claim of SSF 1.0, `legacy` accepts SETs of transmitters that
  follow earlier drafts and put the subject into the event payload.
- `understood-subject-members`: the members of a complex subject the application
  interprets. A SET whose subject has a member the transmitter declared critical
  (`critical_subject_members`) that is not listed is rejected as `invalid_request`.
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
