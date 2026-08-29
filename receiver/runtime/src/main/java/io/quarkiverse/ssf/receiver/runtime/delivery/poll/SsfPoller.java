package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterAuthClientRequestFilter;
import io.quarkiverse.ssf.receiver.runtime.auth.TransmitterTokenProvider;
import io.quarkiverse.ssf.receiver.runtime.dedup.SsfJtiDedupStore;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.SetVerifier;
import io.quarkiverse.ssf.receiver.runtime.delivery.push.SsfVerificationException;
import io.quarkiverse.ssf.receiver.runtime.event.SsfAliases;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventContext;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventHandler;
import io.quarkiverse.ssf.receiver.runtime.event.SsfEventToken;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics.DeliverySource;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics.PollEventFailureReason;
import io.quarkiverse.ssf.receiver.runtime.metrics.SsfReceiverMetrics.PollOutcome;
import io.quarkiverse.ssf.receiver.runtime.stream.SsfStreamClient;
import io.quarkiverse.ssf.receiver.runtime.stream.StreamConfiguration;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;

/**
 * RFC 8936 poll loop. When {@code quarkus.openid-ssf.receiver.delivery-method=POLL}, this bean:
 * <ol>
 * <li>Discovers the poll endpoint URL from the stream's {@code delivery.endpoint_url}
 * (or honors an explicit {@code quarkus.openid-ssf.receiver.poll.endpoint-url} override).</li>
 * <li>Schedules a periodic Vert.x timer that POSTs a poll request, verifies each
 * returned SET via {@link SetVerifier}, and dispatches the event to the
 * application's {@link SsfEventHandler}.</li>
 * <li>Acknowledges successfully-processed {@code jti}s on the next request so the
 * transmitter can advance its cursor.</li>
 * <li>Backs off when the transmitter rate-limits the endpoint ({@code 429}, or
 * {@code 503} with {@code Retry-After}): polling is suspended until the
 * {@code Retry-After} deadline (or {@code poll.rate-limit.fallback-backoff}),
 * capped by {@code poll.rate-limit.max-backoff}. Periodic ticks and
 * {@link #pollNow()} calls inside that window are skipped; with auto-start a
 * one-shot catch-up poll fires as soon as the window closes.</li>
 * </ol>
 *
 * <p>
 * Runs at observer priority 300 — after both the startup validator (100) and
 * the receiver-managed registrar (200), so the {@code stream_id} is available
 * before we try to read the stream config.
 */
@ApplicationScoped
public class SsfPoller {

    private static final Logger LOG = Logger.getLogger(SsfPoller.class);

    /** RFC 6585 §4 — not in {@link Response.Status}. */
    private static final int TOO_MANY_REQUESTS = 429;

    @Inject
    SsfReceiverConfig config;

    @Inject
    SsfStreamClient streamClient;

    @Inject
    SetVerifier verifier;

    @Inject
    SsfEventHandler handler;

    @Inject
    TransmitterTokenProvider tokenProvider;

    @Inject
    SsfPollAckStore ackStore;

    @Inject
    SsfJtiDedupStore dedupStore;

    @Inject
    SsfReceiverMetrics metrics;

    @Inject
    SsfAliases aliases;

    @Inject
    Vertx vertx;

    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final ExecutorService dispatchExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory());

    private volatile URI pollEndpoint;
    private volatile SsfTransmitterPollApi pollApi;
    private volatile Long timerId;

    /**
     * {@link System#nanoTime()} before which no poll request may be sent, set on
     * a rate-limited response. {@code 0} means "not backing off". Monotonic so a
     * wall-clock jump can't extend or collapse the window.
     */
    private volatile long backoffUntilNanos;
    /** One-shot Vert.x timer that resumes polling when the backoff window closes (auto-start only). */
    private volatile Long catchUpTimerId;

    void onStart(@Observes @Priority(300) StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        if (config.deliveryMethod() != SsfReceiverConfig.DeliveryMethod.POLL) {
            return;
        }

        // Try to resolve the poll endpoint eagerly. In receiver-managed mode
        // the registrar publishes stream_id from a background virtual thread,
        // so the read-stream-config call here can race ahead of it; in that
        // case we fall back to lazy resolution on each poll cycle.
        ensurePollApi();

        if (!config.poll().autoStart()) {
            LOG.infof(pollEndpoint != null
                    ? "POLL delivery: poll endpoint resolved to " + pollEndpoint
                            + " — auto-start disabled, application must drive polling via SsfPoller.pollNow()"
                    : "POLL delivery: poll endpoint not resolvable yet (waiting on receiver-managed registration?) — "
                            + "auto-start disabled, application must drive polling via SsfPoller.pollNow()");
            return;
        }

        long intervalMs = Math.max(1L, config.poll().interval().toMillis());
        long startDelayMs = Math.max(0L, config.poll().startDelay().toMillis());
        LOG.infof("POLL delivery: scheduling poll of %s every %dms (start-delay=%dms, max-events=%d, return-immediately=%s)",
                pollEndpoint != null ? pollEndpoint : "(deferred resolution)",
                intervalMs, startDelayMs,
                config.poll().maxEvents(), config.poll().returnImmediately());

        if (startDelayMs == 0L) {
            // Kick off an immediate poll so we don't wait for the first interval to drain a backlog.
            dispatchExecutor.execute(this::pollOnceSafely);
            timerId = vertx.setPeriodic(intervalMs, id -> dispatchExecutor.execute(this::pollOnceSafely));
        } else {
            vertx.setTimer(startDelayMs, firstId -> {
                dispatchExecutor.execute(this::pollOnceSafely);
                timerId = vertx.setPeriodic(intervalMs, id -> dispatchExecutor.execute(this::pollOnceSafely));
            });
        }
    }

    /**
     * Resolves the poll endpoint and builds the REST client, idempotently. Safe
     * to call repeatedly: returns {@code true} when the client is ready,
     * {@code false} when the upstream state isn't available yet (typically the
     * receiver-managed registrar still racing). Failures here are NOT logged at
     * WARN — the caller decides whether the moment is noteworthy.
     */
    private synchronized boolean ensurePollApi() {
        if (pollApi != null) {
            return true;
        }
        try {
            URI endpoint = resolvePollEndpoint();
            pollApi = RestClientBuilder.newBuilder()
                    .baseUri(endpoint)
                    .connectTimeout(config.poll().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.poll().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .register(new TransmitterAuthClientRequestFilter(tokenProvider))
                    .build(SsfTransmitterPollApi.class);
            pollEndpoint = endpoint;
            return true;
        } catch (RuntimeException e) {
            LOG.debugf("Poll endpoint not resolvable yet: %s", e.getMessage());
            return false;
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        Long id = timerId;
        if (id != null) {
            vertx.cancelTimer(id);
            timerId = null;
        }
        cancelCatchUpTimer();
        dispatchExecutor.shutdown();
    }

    private URI resolvePollEndpoint() {
        return config.poll().endpointUrl().orElseGet(this::discoverPollEndpoint);
    }

    private URI discoverPollEndpoint() {
        StreamConfiguration cfg = streamClient.configuration();
        if (cfg.delivery() == null || cfg.delivery().endpointUrl() == null) {
            throw new IllegalStateException(
                    "POLL delivery requires the stream to advertise delivery.endpoint_url; "
                            + "none was returned by the transmitter for stream_id=" + cfg.streamId());
        }
        return cfg.delivery().endpointUrl();
    }

    private void pollOnceSafely() {
        if (!polling.compareAndSet(false, true)) {
            // A previous tick is still in flight — skip this one to avoid stacking requests.
            return;
        }
        long startNanos = System.nanoTime();
        PollOutcome outcome = PollOutcome.FAILURE;
        boolean skipped = false;
        try {
            // Receiver-managed + POLL: the registrar runs on a background
            // virtual thread, so the first few cycles after boot may fire
            // before stream_id is published. Skip those quietly — the next
            // tick re-tries.
            if (!ensurePollApi()) {
                outcome = PollOutcome.SUCCESS;
                return;
            }
            Optional<Duration> backoff = rateLimitRemaining();
            if (backoff.isPresent()) {
                // Transmitter asked us to stay away (Retry-After). Not a cycle —
                // don't record it as one.
                LOG.debugf("Skipping poll tick — transmitter rate limit in effect for another %dms",
                        backoff.get().toMillis());
                skipped = true;
                return;
            }
            outcome = pollOnce();
        } catch (RuntimeException e) {
            LOG.warnf("Poll cycle failed: %s", e.getMessage());
        } finally {
            if (!skipped) {
                metrics.pollCycle(outcome, System.nanoTime() - startNanos);
            }
            polling.set(false);
        }
    }

    /**
     * Time left in the current rate-limit backoff window, or empty when the
     * poller is free to contact the transmitter. Lets an application that drives
     * polling via {@link #pollNow()} find out why a call was a no-op.
     */
    public Optional<Duration> rateLimitRemaining() {
        long until = backoffUntilNanos;
        if (until == 0L) {
            return Optional.empty();
        }
        long remaining = until - System.nanoTime();
        if (remaining <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(remaining));
    }

    /**
     * Turns a failed poll response into a backoff duration when the transmitter
     * asked us to stay away: {@code 429} always (with {@code Retry-After} if
     * sent, otherwise the configured fallback), {@code 503} only when it carries
     * {@code Retry-After}. Returns empty for every other failure.
     */
    private Optional<Duration> backoffFor(Response response) {
        if (response == null) {
            return Optional.empty();
        }
        int status = response.getStatus();
        if (status != TOO_MANY_REQUESTS && status != Response.Status.SERVICE_UNAVAILABLE.getStatusCode()) {
            return Optional.empty();
        }
        Optional<Duration> retryAfter = RetryAfter.parse(response.getHeaderString(HttpHeaders.RETRY_AFTER.toString()),
                Instant.now());
        if (retryAfter.isPresent()) {
            return retryAfter;
        }
        if (status != TOO_MANY_REQUESTS) {
            return Optional.empty();
        }
        return Optional.of(config.poll().rateLimit().fallbackBackoff().orElseGet(() -> config.poll().interval()));
    }

    /**
     * Opens a backoff window of {@code requested}, clamped to
     * {@code poll.rate-limit.max-backoff}, and — when the periodic timer is
     * running — arms a one-shot catch-up poll for the moment the window closes
     * so a long {@code Retry-After} doesn't cost an extra full interval.
     * Returns the effective (clamped) backoff.
     */
    private Duration applyBackoff(Duration requested) {
        Duration max = config.poll().rateLimit().maxBackoff();
        Duration effective = requested.compareTo(max) > 0 ? max : requested;
        if (effective.isNegative()) {
            effective = Duration.ZERO;
        }
        backoffUntilNanos = System.nanoTime() + effective.toNanos();
        if (config.poll().autoStart() && !effective.isZero()) {
            cancelCatchUpTimer();
            // +1ms so the catch-up lands strictly after the deadline and isn't itself skipped.
            catchUpTimerId = vertx.setTimer(effective.toMillis() + 1L, id -> {
                catchUpTimerId = null;
                try {
                    dispatchExecutor.execute(this::pollOnceSafely);
                } catch (RejectedExecutionException shuttingDown) {
                    LOG.debug("Skipping rate-limit catch-up poll — poller is shutting down");
                }
            });
        }
        return effective;
    }

    private void cancelCatchUpTimer() {
        Long id = catchUpTimerId;
        if (id != null) {
            vertx.cancelTimer(id);
            catchUpTimerId = null;
        }
    }

    /** Unwraps the JAX-RS {@link Response} from a REST client failure, or {@code null} if there is none. */
    private static Response responseOf(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebApplicationException wae && wae.getResponse() != null) {
                return wae.getResponse();
            }
        }
        return null;
    }

    private PollOutcome pollOnce() {
        boolean drain = config.poll().drainOnPoll();
        for (int safetyCounter = 0; safetyCounter < 100; safetyCounter++) {
            List<String> acks = ackStore.drainAcks();
            metrics.pollAcksSent(acks.size());
            SsfPollRequest request = new SsfPollRequest(
                    config.poll().maxEvents(),
                    config.poll().returnImmediately(),
                    acks.isEmpty() ? null : acks);

            SsfPollResponse response;
            try {
                response = pollApi.poll(request);
            } catch (RuntimeException e) {
                ackStore.requeueAcks(acks);
                Response httpResponse = responseOf(e);
                Optional<Duration> backoff = backoffFor(httpResponse);
                if (backoff.isPresent()) {
                    boolean rateLimited = httpResponse.getStatus() == TOO_MANY_REQUESTS;
                    Duration effective = applyBackoff(backoff.get());
                    LOG.warnf("Poll request to %s %s: %s — backing off for %dms%s, "
                            + "re-queueing %d acks for next cycle",
                            pollEndpoint, rateLimited ? "was rate limited" : "hit an unavailable transmitter",
                            summarize(e), effective.toMillis(),
                            effective.equals(backoff.get()) ? ""
                                    : " (transmitter asked for " + backoff.get().toMillis()
                                            + "ms, capped by poll.rate-limit.max-backoff)",
                            acks.size());
                    LOG.debugf(e, "Poll request to %s — full stack", pollEndpoint);
                    // Only a genuine 429 is "rate limited" in the metrics; a 503 with
                    // Retry-After is still an outage, we just honor the hint.
                    return rateLimited ? PollOutcome.RATE_LIMITED : PollOutcome.FAILURE;
                }
                LOG.warnf("Poll request to %s failed: %s — re-queueing %d acks for next cycle",
                        pollEndpoint, summarize(e), acks.size());
                LOG.debugf(e, "Poll request to %s — full stack", pollEndpoint);
                return PollOutcome.FAILURE;
            }

            int delivered = handleResponse(response);
            boolean more = response != null && Boolean.TRUE.equals(response.moreAvailable());
            if (!drain || !more || delivered == 0) {
                return PollOutcome.SUCCESS;
            }
            // Loop again: we drained a batch and the transmitter has more.
        }
        LOG.warnf("Poll drain loop hit safety cap of 100 iterations — yielding to next tick");
        return PollOutcome.SUCCESS;
    }

    private int handleResponse(SsfPollResponse response) {
        if (response == null || response.sets() == null || response.sets().isEmpty()) {
            return 0;
        }
        metrics.pollEventsReceived(response.sets().size());
        int handled = 0;
        for (Map.Entry<String, String> entry : response.sets().entrySet()) {
            String jti = entry.getKey();
            String jwt = entry.getValue();
            SsfEventToken event;
            try {
                event = verifier.verify(jwt);
            } catch (SsfVerificationException e) {
                LOG.warnf("Discarding SET jti=%s — verification failed: %s", jti, e.getMessage());
                metrics.pollEventFailed(PollEventFailureReason.VERIFY);
                // Per RFC 8936 §2.3 the transmitter retries unacked SETs; we don't ack a failed verify.
                continue;
            }
            // jti dedup — silently skip but still ack so the transmitter advances
            // its cursor (we DID process this SET earlier, the redelivery is the
            // duplicate).
            if (config.dedup().enabled() && dedupStore.seenBefore(event)) {
                LOG.debugf("Skipping duplicate SET jti=%s iss=%s (poll)", event.jti(), event.iss());
                metrics.dedupSkipped(DeliverySource.POLL);
                ackStore.enqueueAck(event.jti() != null ? event.jti() : jti);
                handled++;
                continue;
            }
            try {
                handler.handle(SsfEventContext.of(event, aliases));
                ackStore.enqueueAck(event.jti() != null ? event.jti() : jti);
                metrics.pollEventHandled();
                if (event.events() != null) {
                    // RFC 8417 §2.2: a SET's `events` is keyed by event-type URI.
                    for (String eventTypeUri : event.events().keySet()) {
                        metrics.eventProcessed(eventTypeUri, event.iss(), DeliverySource.POLL);
                    }
                }
                handled++;
            } catch (RuntimeException e) {
                metrics.pollEventFailed(PollEventFailureReason.HANDLER);
                LOG.errorf(e, "SsfEventHandler threw for jti=%s — leaving unacked", jti);
            }
        }
        return handled;
    }

    private static ThreadFactory virtualThreadFactory() {
        return Thread.ofVirtual().name("ssf-poll-", 1).factory();
    }

    /**
     * One-line summary of an exception for INFO/WARN logs. Some upstream errors
     * (e.g. a transmitter / auth server returning an HTML error page) embed the
     * whole body in {@code getMessage()}; this trims to the first line and caps
     * length so the log stays one row. Full detail is still available via the
     * DEBUG-level stack trace.
     */
    private static String summarize(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int newline = msg.indexOf('\n');
        if (newline >= 0) {
            msg = msg.substring(0, newline);
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return t.getClass().getSimpleName() + ": " + msg.trim();
    }

    /**
     * Triggers a single poll cycle synchronously on the calling thread. Intended
     * for {@code auto-start=false} setups where the application drives polling
     * itself (REST endpoint, Kafka consumer, scheduled job, manual admin button).
     * Throws if {@code delivery-method != POLL} since there's no poll endpoint
     * configured in that case.
     *
     * <p>
     * Returns silently if a poll is already in flight — concurrent invocations
     * coalesce into a single in-progress cycle to keep the ack queue consistent.
     * Also returns without contacting the transmitter while a rate-limit backoff
     * ({@code 429} / {@code Retry-After}) is in effect; check
     * {@link #rateLimitRemaining()} to tell the two apart.
     */
    public void pollNow() {
        if (!config.enabled()) {
            throw new IllegalStateException(
                    "pollNow() called while quarkus.openid-ssf.receiver.enabled=false");
        }
        if (config.deliveryMethod() != SsfReceiverConfig.DeliveryMethod.POLL) {
            throw new IllegalStateException(
                    "pollNow() requires quarkus.openid-ssf.receiver.delivery-method=POLL (was: "
                            + config.deliveryMethod() + ")");
        }
        if (!ensurePollApi()) {
            throw new IllegalStateException(
                    "POLL endpoint is not resolvable yet — in receiver-managed mode this typically "
                            + "means the registrar hasn't published a stream_id, or the transmitter's "
                            + "stream config doesn't advertise delivery.endpoint_url. Retry shortly.");
        }
        pollOnceSafely();
    }

    /**
     * Test/integration hook: enqueue a {@code jti} on the ack queue without going
     * through the verify/dispatch path. Useful when an application wants to
     * defer the ack until durable side-effects have committed elsewhere.
     */
    public void enqueueAck(String jti) {
        ackStore.enqueueAck(jti);
    }
}
