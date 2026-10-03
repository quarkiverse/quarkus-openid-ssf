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
| POLL | `SsfPoller.pollNow()`: requests, acks, error reports, `Retry-After` | `SsfPollScheduler`: a Vert.x periodic timer per transmitter runs `pollNow()` on a virtual thread; easyssf's own scheduler thread is never started |
| Stream | `SsfStreamClient`, `SsfStreamRegistrar` (background virtual thread, retries, delete on shutdown), `SsfReceiverStream`, `SsfStreamVerification` | `SsfReceiverLifecycle` starts and stops the registry with the application; `SsfReceiverStreamClient` is the convenience surface for the default transmitter |
| HTTP | `SsfHttpClient`, `JdkSsfHttpClient` | timeouts and user agent from config, replaceable bean |
| Tokens | `SsfTransmitterTokenProvider`, `ClientCredentialsSsfTransmitterTokenProvider` | `StaticTransmitterTokenProvider`, `OidcTransmitterTokenProvider` over `quarkus-oidc-client`, `NoopTransmitterTokenProvider`; the choice per transmitter at runtime |
| Dedup | `SsfJtiDedupStore`, `InMemorySsfJtiDedupStore`, `JdbcSsfJtiDedupStore` (`easyssf-receiver-jdbc`) | producers: in-memory, or JDBC over the default Agroal datasource with schema creation and cleanup |
| Metrics | `SsfReceiverMetrics`, `MicrometerSsfReceiverMetrics` | registered when `quarkus-micrometer` is present, plus the dedup size gauge |
| Health | `SsfStreamRegistrar.getState()`, `SsfPoller.getLast*()` | `SsfReceiverHealthCheck` (`@Wellness`) when `quarkus-smallrye-health` is present |
| Aliases | `SsfEventTypes` (built-in SSF, CAEP, RISC aliases, `registerAlias`) | `event-aliases.*` registered at startup |
| Dev UI | | `SsfDevJsonRpcService` and two pages |
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
`event-aliases.*`) sit next to them; SmallRye Config matches exact property
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
| 300 | `SsfPollScheduler` | schedules a Vert.x periodic timer per transmitter with POLL delivery and `poll.auto-start=true` |

Shutdown stops the timers, the registrars (deleting the streams when
`delete-on-shutdown`) and the cleanup.

The processor decides nothing at build time except which optional
integrations exist, by capability: `io.quarkus.oidc.client`
(`OidcClientTransmitterTokenProviders`), `io.quarkus.metrics`
(`MicrometerSsfReceiverMetricsProducer`), `io.quarkus.agroal`
(`JdbcSsfJtiDedupStoreProducer` instead of the in-memory producer) and
`io.quarkus.smallrye.health` (`SsfReceiverHealthCheck`). Their classes are named
by string so that they are only loaded when the extension they need is present.

## Hot paths

**PUSH**: Vert.x `BodyHandler` (limit 256 KiB + 1) → `blockingHandler` (worker
thread, as verification may fetch keys) → `SsfPushHandler.handle` → the
`SsfPushResponse` status and, if any, the JSON error body. The handlers run
before the `202`; a failing handler is a `500` and the transmitter delivers the
SET again. That is what RFC 8935 and the conformance suite expect, and it is
the behaviour change of 0.2.0.

**POLL**: timer tick → virtual thread → `SsfPoller.pollNow()`: skipped while a
poll runs or the transmitter asked to wait, fetches while `moreAvailable`,
acknowledges handled SETs and reports invalid ones right away.

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
- **No poll acknowledgement store SPI** (D2): acknowledgements are sent right
  after handling; durability, if ever needed, belongs in easyssf.
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
- **Vert.x timer, not easyssf's scheduler thread**: polling runs on the
  application's event infrastructure and shows up in its thread dumps; the
  virtual thread per poll keeps the event loop and worker pool free.
- **Metadata at startup in the background**: a transmitter that is down shows up
  in the log before the first SET without holding the start up.
