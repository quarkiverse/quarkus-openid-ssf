# Receiver: design (as built on easyssf)

Design notes for the `quarkus-openid-ssf-receiver` extension under `receiver/`.
For *how to use* it, see the [root README](../README.md); this is the "why is
the code shaped this way" reference. The port from the extension's own
protocol code to easyssf is described in
[`scratch/plans/adopt-easyssf.md`](../scratch/plans/adopt-easyssf.md) (not
versioned) and summarized in the [changelog](../CHANGELOG.md).

## Goal

Let a Quarkus application act as an OpenID Shared Signals Framework receiver
with the least Quarkus specific code: the protocol lives in
[easyssf](https://github.com/easyssf/easyssf), the library shared with
the Spring Boot starter, so there is one implementation of SET verification,
metadata discovery, stream management, PUSH and POLL delivery and
de-duplication, one place to fix bugs, and the extension inherits the OpenID
conformance suite results of easyssf.

## What easyssf provides and what the extension adds

| Concern | easyssf (`org.easyssf:easyssf-receiver`) | Extension (`io.quarkiverse.ssf.receiver.runtime`) |
|---|---|---|
| Configuration | setters on the parts | `SsfReceiverConfig` / `SsfTransmitterConfig` (`@ConfigMapping`), `SsfTransmitterFactory` maps them onto easyssf |
| One transmitter | `SsfTransmitter` (metadata resolver, verifier, token provider, stream client, stream, verification, registrar, poller, push header) and `SsfTransmitters` (registry, issuer routing) | `SsfReceiverProducers` builds one `SsfTransmitter` per configured transmitter and the registry; `SsfTransmitterCustomizer` beans hook into the builder |
| SET verification | `NimbusSsfSetVerifier`, JWK Set caching, `IssuerRoutingSsfSetVerifier` | config of algorithms, key size, type header, clock skew, audience |
| Processing | `SsfSetProcessor`: verify, stream verification state, dedup, handlers, metrics | the handler list from `Instance<SsfEventHandler>`, ordered by `@Priority` |
| PUSH | `SsfPushHandler.handle(authHeader, body)` → `SsfPushResponse` | `SsfPushRoute`: Vert.x route, `BodyHandler`, blocking handler |
| POLL | `SsfPoller`: `start()` runs the loop on a thread from its `ThreadFactory` (periodic or long polling), `pollNow()`, acks and error reports in an `SsfPollAckStore` (`InMemorySsfPollAckStore`, `JdbcSsfPollAckStore`), `Retry-After`, the pending-acks gauge | `SsfTransmitterFactory` configures the poller from `poll.*` with a virtual thread named `ssf-poller-<name>`; `SsfPollScheduler` starts it at startup (unless `poll.auto-start=false`) and stops it first on shutdown; producers for the ack store |
| Stream | `SsfStreamClient`, `SsfStreamRegistrar` (background virtual thread, retries, delete on shutdown), `SsfReceiverStream`, `SsfStreamVerification` | `SsfReceiverLifecycle` starts and stops the registry with the application; `SsfReceiverStreamClient` is the convenience surface for the default transmitter |
| HTTP | `SsfHttpClient`, `JdkSsfHttpClient` | timeouts and user agent from config, replaceable bean |
| Tokens | `SsfTransmitterTokenProvider`, `ClientCredentialsSsfTransmitterTokenProvider` | `StaticTransmitterTokenProvider`, `OidcTransmitterTokenProvider` over `quarkus-oidc-client`, `NoopTransmitterTokenProvider`; the choice per transmitter at runtime |
| Dedup | `SsfJtiDedupStore` (`claim` / `processed` / `forget` with a lease), `InMemorySsfJtiDedupStore`, `JdbcSsfJtiDedupStore` (`easyssf-receiver-jdbc`), `SsfSetInProgressException` | producers: in-memory, or JDBC over the default Agroal datasource with schema creation, upgrades (`JdbcSsfSchema.prepareTable` with `processedSetUpgrades`) and cleanup; `dedup.lease` |
| Metrics | `SsfReceiverMetrics`, `MicrometerSsfReceiverMetrics` (the pending-acks gauge is registered by `SsfPoller.start()`) | registered when `quarkus-micrometer` is present, plus the dedup size gauge |
| Health | `SsfStreamRegistrar.getState()`, `SsfPoller.getLast*()`, `getPendingAckCount()` | `SsfReceiverHealthCheck` (`@Wellness`) when `quarkus-smallrye-health` is present |
| Aliases | `SsfEventTypes` (built-in SSF, CAEP, RISC, SCIM aliases, `registerAlias`) | `event-aliases.*` registered at startup |
| SCIM Events | `SsfScimEvent`, `SsfScimSubject`, `SsfScimOperation` (easyssf-core), `SsfScimEventHandler` (easyssf-receiver) | nothing: a bean extending `SsfScimEventHandler` is an `SsfEventHandler`; `example-scim-provisioning` |
| Dev UI | | `SsfDevJsonRpcService` and two pages; `pollStatus()` shows the pollers |
| Tests | `easyssf-test`: `TestTransmitter` | `QuarkusExtensionTest`s in `receiver/deployment`, see below |

Nothing in easyssf-core or easyssf-receiver uses reflection or Jackson; JSON is
parsed with the support shaded in Nimbus. That is what makes the native image
of the examples work without extra configuration; the processor only indexes
the easyssf jars (so application code can use their types in resource
signatures) and registers the core records for reflection for applications
that serialize them.

## Configuration

`quarkus.openid-ssf.receiver.*` holds the settings of the transmitter named
`default` and, with `@WithParentName` + `@WithUnnamedKey`, the map of named
transmitters: `quarkus.openid-ssf.receiver.<name>.*` is the same
`SsfTransmitterConfig` for another transmitter. The idiom of `quarkus.oidc`.
Receiver-wide groups (`push.endpoint-path`, `http.*`, `dedup.*`, `jdbc.*`,
`event-aliases.*`) sit next to them; `poll.long-polling` and
`poll.long-polling-hold` carry the names of the Spring Boot starter, so both
integrations share one vocabulary; SmallRye Config matches exact property
names before map keys, so the receiver-wide `push.endpoint-path` and the default
transmitter's `push.expected-auth-header` coexist under `push.`.

`transmitter-issuer` is optional in the mapping so that `enabled=false` works
without a transmitter; `SsfTransmitterFactory` turns a missing or invalid value
into a `ConfigurationException` naming the property when the transmitters are
built, which happens at startup.

## Beans and lifecycle

All producers are `@DefaultBean` and `@Singleton`: an application bean of the
same type replaces one. `@Singleton` rather than `@ApplicationScoped` because
the easyssf types are final (no client proxies); laziness where it matters
(`enabled=false`, no transmitter configured) comes from `Instance<...>`
injection points.

Startup order (`StartupEvent` observer priorities):

| Priority | Bean | Does |
|---|---|---|
| `Router` observer | `SsfPushRoute` | registers the push route when a configured transmitter delivers by PUSH |
| 200 | `SsfReceiverLifecycle` | registers the event aliases, builds the transmitters (configuration errors fail the start), `SsfTransmitters.start()` (the registrars), resolves the metadata in the background for the log, starts the JDBC cleanup |
| 300 | `SsfPollScheduler` | `SsfPoller.start()` for every transmitter with POLL delivery and `poll.auto-start=true`; the thread factory of the poller gives a virtual thread `ssf-poller-<name>` |

Shutdown: `SsfPollScheduler` (`ShutdownEvent` priority 100) stops the pollers
first, which flushes the pending acknowledgements to the transmitter with a last
request and removes them from the JDBC store, then `SsfReceiverLifecycle`
(default priority) stops the registrars (deleting the streams when
`delete-on-shutdown`) and the cleanup. Agroal closes the datasource when the
container shuts down, after the observers.

The processor decides nothing at build time except which optional
integrations exist, by capability: `io.quarkus.oidc.client`
(`OidcClientTransmitterTokenProviders`), `io.quarkus.metrics`
(`MicrometerSsfReceiverMetricsProducer`), `io.quarkus.agroal`
(`JdbcSsfJtiDedupStoreProducer` and `JdbcSsfPollAckStoreProducer` instead of the
in-memory producers) and
`io.quarkus.smallrye.health` (`SsfReceiverHealthCheck`). Their classes are named
by string so that they are only loaded when the extension they need is present.

## Hot paths

**PUSH**: Vert.x `BodyHandler` (limit 256 KiB + 1) → `blockingHandler` (worker
thread, as verification may fetch keys) → `SsfPushHandler.handle` → the
`SsfPushResponse` status and, if any, the JSON error body. The handlers run
before the `202`; a failing handler is a `500` and the transmitter delivers the
SET again. That is what RFC 8935 and the conformance suite expect, and it is
the behaviour change of 0.2.0.

**POLL**: the poller's own virtual thread → `SsfPoller` loop: sleeps the
interval (short polling) or sends the next request at once (long polling, the
transmitter holds it), skipped while the transmitter asked to wait, fetches
while `moreAvailable`, records acknowledgements and error reports in the
`SsfPollAckStore` and sends them with the next request (right away in short
polling). `SsfSetProcessor` claims each SET in the dedup store before the
handlers run; a SET another instance holds is left unacknowledged.

## Tests

`receiver/deployment` tests boot Quarkus with `QuarkusExtensionTest` against
`TestTransmitter`, started in `setBeforeAllCustomizer` (the class loader of
the test). Test methods run in the Quarkus class loader, so the archive sets
`quarkus.class-loading.parent-first-artifacts` for `easyssf-test`, `easyssf-core`
and Nimbus; both class loaders then share the classes, and the instance kept
in a holder class loaded through the system class loader (`TestTransmitterHolder`) can be driven from a test method
(queue a SET, take the transmitter down, expire tokens). The old WireMock
helpers and their system property bridging are gone.

The examples have `@QuarkusTest`s with a `QuarkusTestResourceLifecycleManager`
around `TestTransmitter`, and `@QuarkusIntegrationTest`s that run them against
the native binary in the native workflow.

## Decisions

- **Handlers before the response** (D1 of the plan): adopted from easyssf.
- **Poll acknowledgement store from easyssf** (D2 of the 0.2.0 plan, revisited
  with easyssf 0.3.0): the extension dropped its own `SsfPollAckStore` SPI in
  0.2.0; easyssf 0.3.0 brought one back, with a JDBC implementation, and the
  extension only produces the bean.
- **Metric names of easyssf** (D3): one dashboard for Spring Boot and Quarkus
  receivers.
- **Issuer aliases become transmitter names** (D4): the `transmitter` tag is the
  name a transmitter is configured under.
- **OAuth2 extras kept** (D5): `additional-params` and `expiry-safety-window`
  map onto easyssf setters; `grant-type` and `timeout` are gone.
- **Thin stream client kept** (D6): `SsfReceiverStreamClient`, as the Dev UI and
  the examples want the stream of the default transmitter without its id.
- **Several transmitters** (D7): done in the same release, the registry is there
  anyway.
- **The poller's own loop, not a Vert.x timer** (since 0.4.0): easyssf 0.3.0
  runs the loop on a thread from a `ThreadFactory`, which long polling needs
  (one request always outstanding) and which registers the pending-acks gauge
  and flushes the acknowledgements on `stop()`. One code path for short and long
  polling; the thread is a virtual one named per transmitter, so nothing blocks
  the event loop or the worker pool. `pollNow()` stays for
  `poll.auto-start=false`.
- **`initialize-schema` stays a boolean** (D4 of the 0.3.0 plan): `true` creates
  and upgrades, `false` refuses with the statements to run; Quarkus Dev Services
  already decide what is embedded, so the Spring Boot enum
  (`embedded`/`always`/`never`) adds nothing here.
- **No `synchronized`**: the extension's code locks with `ReentrantLock`, as
  `synchronized` pins virtual threads before JDK 24 and the project targets 21.
- **Metadata at startup in the background**: a transmitter that is down shows up
  in the log before the first SET without holding the start up.
