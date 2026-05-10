# quarkus-ssf-receiver — design (as built)

This document describes the extension's architecture as currently implemented.
For *how to use* the extension, see the [root README](../README.md). For
*how to contribute*, see [CONTRIBUTING](../CONTRIBUTING.md). This doc is the
"why is the code shaped this way" reference.

The original v1 brief lived here too; it's preserved at the bottom under
[Historical context](#historical-context-original-v1-brief) for diff against
the implementation.

---

## Goal

A Quarkus extension that lets a Quarkus application act as an
[OpenID Shared Signals Framework
(SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
receiver against any compliant SSF transmitter (Keycloak, [caep.dev](https://ssf.caep.dev),
custom). The extension owns:

- the **wire layer** (verifying signed SETs per RFC 8417, fetching JWKS,
  speaking the management API),
- the **stream lifecycle** (transmitter-managed *or* receiver-managed,
  discover-or-create on startup),
- the **delivery loop** (PUSH route or POLL scheduler),
- the **observability + admin surface** (metrics + Dev UI).

The application owns the **business logic** — implementing
`SsfEventHandler` and reacting to events.

## Scope (as built)

| Area | Status | Notes |
|---|---|---|
| Stream management — `TRANSMITTER` | ✅ | Operator pre-creates stream, app gets `stream-id`. Startup probe reads config + status. |
| Stream management — `RECEIVER` | ✅ | Discover-or-create against `configuration_endpoint`, background retry with backoff, optional delete-on-shutdown. |
| Delivery — `PUSH` (RFC 8935) | ✅ | Vert.x route, sync verification, async dispatch on virtual-thread executor. |
| Delivery — `POLL` (RFC 8936) | ✅ | Periodic Vert.x timer, drain-on-poll, ack queue, manual `pollNow()`. |
| Stream client | ✅ | Read / list / create / update (PUT) / patch (PATCH per spec §8.1.1.3) / delete; status read+update; subjects add/remove; verification request. |
| `jti` deduplication | ✅ | `SsfJtiDedupStore` SPI; in-memory default; composite `iss::jti` key. |
| Outbound auth | ✅ | Static bearer / OIDC `client_credentials` / Noop — selected at build time. |
| Metrics | ✅ | Optional via Micrometer; per-event-type, per-transmitter counters with alias resolution. |
| Aliases | ✅ | URI → short name for event types, issuer URLs, and the receiver itself. |
| Dev UI | ✅ | Two pages: Stream Management (status, subjects, verify, configuration, aliases) and Transmitter Metadata (raw `.well-known/ssf-configuration` view). |
| Disable kill-switch | ✅ | `ssf.receiver.enabled=false` no-ops every startup observer. |
| Native image | ✅ | Compiles to native; CI verifies on every PR. |

Out of scope today (see [Future considerations](#future-considerations)):

- Built-in durable persistence — both `SsfJtiDedupStore` and `SsfPollAckStore`
  ship in-memory defaults; consumers needing exactly-once across restarts
  provide their own `@ApplicationScoped` impl.
- Multi-transmitter receivers — one transmitter per app instance.
- Per-event-type CAEP / RISC parsing — `SsfEventToken.events()` exposes
  the raw `Map<eventTypeURI, payload>` (RFC 8417 §2.2).

## Module layout

| Module | Responsibility |
|---|---|
| `runtime/` | Everything that runs in the consumer's JVM: config mapping, beans, REST clients, SPIs. Imports its optional integrations (`quarkus-oidc-client`, `quarkus-micrometer`) with `<optional>true</optional>` so consumers don't pay the transitive cost. |
| `deployment/` | The build-time `SsfReceiverProcessor` plus the Dev UI processor. Decides which token-provider / metrics impl to register based on classpath capabilities and `ConfigProvider`-readable config at build time. Hosts the smoke test. |
| `examples/example-transmitter-managed-stream/` | Consumer app — TRANSMITTER mode + PUSH against Keycloak. |
| `examples/example-receiver-managed-stream/` | Consumer app — RECEIVER mode against caep.dev (default profile, static-token + PUSH) or Keycloak (`-Dquarkus.profile=keycloak`, OIDC + POLL). |

The runtime artifact's `quarkus-extension.properties` descriptor points at
`com.easyssf:quarkus-ssf-receiver-deployment` so Quarkus can locate the
deployment processor at build time.

## Runtime components

```
runtime/src/main/java/com/easyssf/quarkus/ssfreceiver/runtime/
├── SsfReceiverConfig.java                                # @ConfigMapping("ssf.receiver")
├── auth/
│   ├── TransmitterTokenProvider.java                    # SPI for outbound bearer token
│   ├── NoopTransmitterTokenProvider.java                # @DefaultBean, no Authorization header
│   ├── StaticTransmitterTokenProvider.java              # registered when ssf.receiver.transmitter-access-token is set
│   ├── OidcTransmitterTokenProvider.java                # registered when quarkus-oidc-client is on classpath
│   └── TransmitterAuthClientRequestFilter.java          # JAX-RS filter that attaches the token
├── dedup/
│   ├── SsfJtiDedupStore.java                            # SPI: boolean seenBefore(SsfEventToken)
│   └── InMemorySsfJtiDedupStore.java                    # @DefaultBean, bounded LinkedHashMap with FIFO eviction
├── delivery/
│   ├── push/
│   │   ├── SsfPushRoute.java                            # Vert.x route at config.push().endpointPath()
│   │   ├── SetVerifier.java                             # JWS signature + iss/iat/jti/aud claim validation
│   │   ├── SsfVerificationException.java
│   │   └── JwksResolver.java                            # thin wrapper over Nimbus JWKSourceBuilder
│   └── poll/
│       ├── SsfPoller.java                               # periodic Vert.x timer + manual pollNow()
│       ├── SsfPollAckStore.java                         # SPI: enqueueAck / drainAcks / requeueAcks / size
│       ├── InMemorySsfPollAckStore.java                 # @DefaultBean, ConcurrentLinkedDeque
│       ├── SsfTransmitterPollApi.java                   # MP REST client — POST <poll_endpoint>
│       ├── SsfPollRequest.java / SsfPollResponse.java
├── devui/
│   └── SsfDevJsonRpcService.java                        # Quarkus Dev UI JsonRPC backend
├── event/
│   ├── SsfEventToken.java                               # verified inbound SET, exposed to consumer
│   ├── SsfEventHandler.java                             # consumer SPI
│   ├── LoggingSsfEventHandler.java                      # @DefaultBean handler
│   ├── SsfReceiverStartupValidator.java                 # validates required config at startup
│   └── SsfAliases.java                                  # event-type, issuer, receiver alias resolution
├── metadata/
│   ├── SsfTransmitterMetadata.java                      # parsed .well-known/ssf-configuration record
│   ├── SsfTransmitterMetadataApi.java                   # MP REST client — GET <metadata-url>
│   ├── SsfConfigurationResolver.java                    # lazy + 5-min-cached fetcher; logs URL at startup
│   └── SsfMetadataException.java
├── metrics/
│   ├── SsfReceiverMetrics.java                          # SPI for hot-path metric emission
│   ├── NoopSsfReceiverMetrics.java                      # @DefaultBean
│   └── MicrometerSsfReceiverMetrics.java                # registered only when quarkus-micrometer is on classpath
└── stream/
    ├── ReceiverManagedStreamRegistrar.java              # discover-or-create with virtual-thread retry loop
    ├── ReceiverManagedStreamState.java                  # holds the assigned stream_id at runtime
    ├── TransmitterManagedStreamProbe.java               # startup config + status fetch in TRANSMITTER mode
    ├── SsfStreamClient.java                             # public CDI bean for management ops
    ├── SsfTransmitterStreamConfigurationApi.java        # GET / POST / PUT / PATCH / DELETE <configuration_endpoint>
    ├── SsfTransmitterStreamStatusApi.java               # GET / POST <status_endpoint>
    ├── StreamConfiguration.java + StreamConfigurationDto.java
    ├── StreamStatus.java + StreamStatusDto.java         # — under stream/status/
    ├── StreamLogFormat.java                             # shared log-line formatters
    ├── SsfStreamException.java
    ├── subjects/                                        # add_subject / remove_subject endpoints
    └── verification/                                    # verification_endpoint
```

### Startup-observer priority order

`StartupEvent` observers run in `@Priority` order. Earlier numbers run first.

| Priority | Observer | Side effects |
|---|---|---|
| 50 | `SsfConfigurationResolver.onStart` | Logs the resolved metadata URL (no fetch). |
| 100 | `SsfReceiverStartupValidator.onStart` | Throws on missing required config (after the resolver, so the URL is in the log even if validation fails). |
| 200 | `TransmitterManagedStreamProbe.onStart` (TRANSMITTER mode) | Fetches stream config + status; logs the result. Failures are warnings, not fatal. |
| 200 | `ReceiverManagedStreamRegistrar.onStart` (RECEIVER mode) | For pinned-id paths: synchronous probe. For discover-or-create: spawns a virtual-thread retry loop, returns immediately. |
| 300 | `SsfPoller.onStart` (POLL mode) | Resolves poll URL, builds REST client, schedules periodic timer (with optional `start-delay`). |

The Vert.x router observer (`SsfPushRoute.registerRoute`) is *not* keyed by
`@Priority` — Vert.x fires it during HTTP startup, which can interleave
with the priority ladder. In practice the push route is registered before
or shortly after the priority-50 observer.

## Configuration surface

Defined in `SsfReceiverConfig` (`@ConfigRoot(phase = RUN_TIME)`,
`@ConfigMapping(prefix = "ssf.receiver")`).

| Property | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Master kill switch. |
| `transmitter-issuer` | URI | required | Issuer URL of the transmitter. |
| `transmitter-metadata-url` | URI | derived | Per SSF §7.2 — `<host>/.well-known/ssf-configuration<issuer-path>`. |
| `transmitter-jwks-url` | URI | from metadata | Override the discovered `jwks_uri`. |
| `transmitter-access-token` | string | unset | When set, the deployment processor registers `StaticTransmitterTokenProvider` (and skips OIDC even if on classpath). |
| `expected-audience` | string | unset | If set, every inbound SET must carry a matching `aud`. |
| `stream-management` | enum | `TRANSMITTER` | `TRANSMITTER` or `RECEIVER`. |
| `stream-id` | string | unset | Required in TRANSMITTER mode; in RECEIVER mode pins the id and disables the discover-or-create dance. |
| `delivery-method` | enum | `PUSH` | `PUSH` or `POLL`. |
| `events-requested` | List\<URI\> | unset | Required in RECEIVER mode (used as `events_requested` on create-stream). |
| `event-aliases.<alias>` | Map\<String, URI\> | built-ins for SSF spec | Used as the `event` Micrometer tag. |
| `issuer-aliases.<alias>` | Map\<String, URI\> | empty | Used as the `iss` Micrometer tag. |
| `alias` | string | `expected-audience` then `"unknown"` | Used as the `receiver` Micrometer tag. |
| `oidc-client-token-timeout` | Duration | `2s` | OIDC token-fetch wait cap. |
| `push.endpoint-path` | string | `/ssf/push` | Relative to `quarkus.http.root-path`. |
| `push.expected-auth-header` | string | unset | If set, push endpoint requires this exact `Authorization` header. |
| `push.delivery-endpoint-url` | URI | unset | Required in RECEIVER + PUSH; sent as `delivery.endpoint_url` on create-stream. |
| `poll.endpoint-url` | URI | discovered | Override; usually discovered from `delivery.endpoint_url`. |
| `poll.auto-start` | bool | `true` | When `false`, app drives via `SsfPoller.pollNow()`. |
| `poll.start-delay` | Duration | `0s` | Pre-first-poll delay. |
| `poll.interval` | Duration | `30s` | Period. |
| `poll.max-events` | int | `100` | RFC 8936 §2.1. |
| `poll.return-immediately` | bool | `true` | Long-poll when `false`. |
| `poll.drain-on-poll` | bool | `true` | If `moreAvailable=true`, keep polling synchronously. |
| `poll.timeout` | Duration | `30s` | Connect/read timeout. |
| `dedup.enabled` | bool | `true` | Master switch for `SsfJtiDedupStore` consultation. |
| `dedup.capacity` | int | `10000` | In-memory default's bounded LRU size. |
| `transmitter-managed.probe-on-startup` | bool | `true` | Fetch + log stream config + status in TRANSMITTER mode. |
| `receiver-managed.register-stream` | bool | `true` | Run the discover-or-create dance. |
| `receiver-managed.delete-on-shutdown` | bool | `false` | DELETE the stream on shutdown. |
| `receiver-managed.description` | string | unset | `description` field on create-stream. |

## Consumer SPIs

The extension exposes four SPIs. All have `@DefaultBean` no-op / in-memory
implementations so a consumer who just wants events delivered to their
handler doesn't have to implement any of them.

```java
// 1. The handler — what consumers always implement.
public interface SsfEventHandler {
    void handle(SsfEventToken event);
}

// 2. jti dedup. Default: bounded in-memory LRU.
public interface SsfJtiDedupStore {
    boolean seenBefore(SsfEventToken event);   // atomic check-and-record
    int size();
}

// 3. POLL ack queue. Default: in-memory ConcurrentLinkedDeque.
public interface SsfPollAckStore {
    void enqueueAck(String jti);
    List<String> drainAcks();
    void requeueAcks(Collection<String> jtis);
    int size();
}

// 4. Outbound bearer-token provider. Default: noop.
public interface TransmitterTokenProvider {
    Optional<String> accessToken();
}
```

`SsfEventToken` is a record exposing the verified SET — `jti`, `iss`, `iat`,
`aud`, `events`, `subjectId`, `txn`, plus `additionalProperties` (Map of
unmodelled / future JWT claims like `exp`, `nbf`, transmitter-specific
extensions).

## Build-time decisions

`SsfReceiverProcessor` makes three classpath-dependent decisions at build
time. Each runs in its own `@BuildStep`; the decisions are baked into the
final jar / native image.

| Decision | Trigger | Effect |
|---|---|---|
| Static vs OIDC vs Noop token provider | `ssf.receiver.transmitter-access-token` set (read via `ConfigProvider` at build time); `quarkus-oidc-client` capability | Static wins if both eligible. Static-token check uses `ConfigProvider` so the active profile is honored. |
| Micrometer metrics impl | `io.quarkus.metrics` capability (provided transitively by `quarkus-micrometer`) | When present, `MicrometerSsfReceiverMetrics` is registered; otherwise the noop default stays in effect. The Micrometer impl is `@Startup` so meters appear at `/q/metrics` from the first scrape, not on first event. |
| REST client interface indexing | always | Every interface used by `RestClientBuilder.build(...)` is added to the Jandex index — required because runtime jar classes aren't in the application archive by default. |

In dev mode, profile changes trigger a rebuild and these decisions re-run.
For a packaged jar, repackage with the desired profile.

## Hot path

### PUSH (`SsfPushRoute.handle`)

```
Vert.x request
  └─> [if expected-auth-header configured] check Authorization → 401 on mismatch
  └─> read body → 400 if blank
  └─> SetVerifier.verify(body) → 400 on parse / signature / claim failure
  └─> metrics.pushAccepted()
  └─> [if dedup.enabled] dedupStore.seenBefore(event) → metrics.dedupSkipped + 202 (skip handler)
  └─> recordEventTypes() — per-type counter increments via SsfAliases
  └─> dispatchExecutor (virtual-thread): handler.handle(event)
       └─> handler exception → metrics.pushHandlerError + log
  └─> 202
```

### POLL (`SsfPoller.pollOnce`)

```
loop (drain-on-poll):
  ackStore.drainAcks()
  metrics.pollAcksSent(count)
  pollApi.poll(SsfPollRequest{maxEvents, returnImmediately, ack})
    └─> failure → ackStore.requeueAcks(acks); return outcome=FAILURE
  metrics.pollEventsReceived(response.sets().size())
  for each (jti, jwt) in response.sets:
    SetVerifier.verify(jwt)
      └─> failure → metrics.pollEventFailed(VERIFY); skip (don't ack)
    if dedup.enabled and dedupStore.seenBefore(event):
      metrics.dedupSkipped(POLL); ackStore.enqueueAck(jti)  # ack — we did process it earlier
    else:
      handler.handle(event)
        └─> exception → metrics.pollEventFailed(HANDLER); skip ack
        └─> success → ackStore.enqueueAck(jti); metrics.pollEventHandled
                      per-type counter via SsfAliases
  if not (drain-on-poll and moreAvailable and delivered>0): break
metrics.pollCycle(outcome, durationNanos)
```

`SsfPoller.pollNow()` is the public entry point for `auto-start=false` mode;
it short-circuits with `IllegalStateException` if `enabled=false`,
`delivery-method != POLL`, or the poller hasn't initialized yet.

### Receiver-managed registration retry loop

```
Thread.ofVirtual().name("ssf-receiver-managed-registrar").start(() -> {
    long delay = 1s
    while (!stopped && !state.streamId().isPresent()) {
        try {
            registerOnce(deliveryUrl)   # list + match-or-create + state.setStreamId
            return                      # success
        } catch (RuntimeException e) {
            log warn (one line, full stack at debug)
        }
        sleep(delay)
        delay = min(delay * 2, 30s)
    }
})
```

Push route is registered independently and is functional immediately — the
receiver can accept inbound SETs even while registration is still in
flight. The Dev UI's "Stream Management" page reads
`registrationStatus()` (lightweight, no outbound call) before its main
JsonRpc calls and shows *"Stream registration in progress…"* until the
loop succeeds.

## Observability

### Metrics (Micrometer, optional)

Namespace `ssf.receiver.*`. Pre-registered at startup so they appear at
the first scrape with value `0`:

- **Push:** `push.accepted`, `push.rejected{reason}`, `push.handler.errors`
- **Poll:** `poll.cycles{outcome}` (timer), `poll.events.received`,
  `poll.events.handled`, `poll.events.failed{reason}`, `poll.acks.sent`,
  `poll.ack.queue.depth` (gauge)
- **Per-event:** `events.processed{event,iss,receiver,delivery}` —
  dynamically tagged using `SsfAliases`
- **Dedup:** `dedup.skipped{delivery}`, `dedup.store.size` (gauge)

### Dev UI

Two web-component pages registered by `SsfReceiverDevUIProcessor`,
backed by `SsfDevJsonRpcService`:

- **Stream Management** (`qwc-ssf-stream-management.js`) — receiver alias,
  description, stream id, status, configuration (with inline event-type +
  issuer aliases), enable/pause/disable controls, subject add/remove,
  verify-event trigger, manual poll trigger.
- **Transmitter Metadata** (`qwc-ssf-transmitter-metadata.js`) — parsed
  view of the `.well-known/ssf-configuration` document.

### Startup logging

Each major observer logs a single INFO line summarizing the resolved state
— metadata URL, stream id + status + delivery, push registration, POLL
schedule. Failures degrade to WARN with a one-line message + DEBUG stack.

## Wire compatibility

- Inbound SETs: RFC 8417 (Security Event Token) + the SSF profile
  additions (`sub_id`, `txn`).
- PUSH: RFC 8935 (HTTP delivery profile) — `application/secevent+jwt`.
- POLL: RFC 8936 (poll-based delivery) — `{maxEvents, returnImmediately, ack}`
  request, `{sets, moreAvailable}` response.
- Stream management: SSF spec §7.1.1 (configuration_endpoint CRUD), §8.1.2
  (status_endpoint), §8.1.3 (subjects), §8.1.4 (verification),
  §7.2 (well-known metadata derivation).
- Forward compatibility: unknown JWT claims flow into
  `SsfEventToken.additionalProperties`; unknown metadata fields flow into
  `SsfTransmitterMetadata.additionalProperties`; the
  `StreamConfigurationDto.DeliveryDto` keeps unknown fields via
  `@JsonAnyGetter`/`@JsonAnySetter`.

## Future considerations

- **Durable persistence defaults** — ship a `quarkus-ssf-receiver-jdbc`
  sub-extension providing JDBC-backed `SsfPollAckStore` +
  `SsfJtiDedupStore`. Same pattern as `quarkus-oidc-db-token-state-manager`.
- **Multi-transmitter receivers** — config becomes a map keyed by
  transmitter alias; one `SsfStreamClient` / `SsfPoller` per entry; each
  registrar runs independently. Likely a v2 effort.
- **Per-event-type CAEP / RISC parsing** — emit typed `CaepSessionRevoked`
  etc. records as CDI events alongside the raw `SsfEventToken`. Optional
  sub-extension so consumers who only need the raw map don't pay.
- **Native runtime smoke test** — current native CI only verifies the
  example apps compile native. Adding a `@QuarkusIntegrationTest` that
  boots the native binary and round-trips a SET would close the loop.
- **Quarkiverse adoption** — once stable, propose moving to
  `io.quarkiverse.ssfreceiver`. Costs one rename, gains shared CI / release
  / discovery infrastructure.

---

## Historical context — original v1 brief

The v1 brief was deliberately small ("PUSH only, TRANSMITTER only, no
dedup, no POLL") so the first iteration could ship in a few days and prove
the architecture. The implementation has since grown to cover everything
labelled "out of scope" in that brief — adding RECEIVER mode, POLL,
dedup, persistence SPIs, and metrics — without breaking the original
config slot names (the v1 brief explicitly reserved them).

The original brief is preserved below for reference / archaeology.

> **Goal.** Build a reusable Quarkus extension `quarkus-ssf-receiver`
> that lets a Quarkus app act as a Shared Signals Framework (SSF)
> receiver against a Keycloak SSF transmitter.
>
> **Scope — v1.** TRANSMITTER stream management only; PUSH delivery only.
> No `jti` dedup, no persistence, no POLL, no RECEIVER mode.
>
> **Consumer SPI.**
> ```java
> public interface SsfEventHandler {
>     void handle(SsfEvent event);
> }
> public record SsfEvent(String jti, String iss, Instant iat, String rawJwt) {}
> ```
>
> **Out of scope (deferred but config-reserved):** RECEIVER-managed
> mode, POLL delivery, CAEP/RISC-specific event parsing, jti dedup,
> persistence.
>
> The current implementation supersedes this brief — the deferred items
> are now in scope, and `SsfEvent` evolved into `SsfEventToken` with the
> richer field set described above.
