package io.quarkiverse.ssf.receiver.example.scim;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;

import io.quarkus.runtime.StartupEvent;

/**
 * Plays the SCIM service provider: every operation on a user becomes a SCIM Event (RFC
 * 9967) that the demo transmitter delivers to the receiver of this very application on
 * its next poll. The operations are driven by {@link DemoScimProviderResource}
 * ({@code /demo/scim}) and, unless {@code demo.autoplay=false}, by {@link #play()}, which
 * tells the life of two users once the receiver has registered its stream, one SET every
 * few seconds. The SETs transmitted are kept, decoded, for {@code GET /demo/scim/events}:
 * that is what a SCIM Event looks like on the wire.
 *
 * <p>
 * The transmitter is the one {@link ExampleScimProvisioning#main} started; without it
 * (in the tests, or with a configured transmitter) the demo is not running.
 */
@Singleton
public class DemoScimProvider {

    private static final Logger LOG = Logger.getLogger(DemoScimProvider.class);

    private static final Duration PAUSE = Duration.ofSeconds(4);

    static final String ALICE = "2b2f880af6674ac284bae9381673d462";

    static final String BOB = "c3a6e1f0b2d94f0e9a7c5d8b6e4f2a10";

    static volatile TestTransmitter transmitter;

    private final List<TransmittedSet> sets = new CopyOnWriteArrayList<>();

    private final Map<String, Integer> versions = new ConcurrentHashMap<>();

    private final Map<String, String> externalIds = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "demo.autoplay", defaultValue = "true")
    boolean autoplay;

    /** A SET as transmitted: the compact JWT and its decoded claims. */
    public record TransmittedSet(String jwt, Map<String, Object> claims) {
    }

    void onStart(@Observes StartupEvent event) {
        if (!isRunning()) {
            return;
        }
        if (autoplay) {
            Thread.ofVirtual().name("demo-scim-provider").start(this::play);
        } else {
            LOG.info("The demo SCIM service provider waits for requests to /demo/scim, demo.autoplay is off");
        }
    }

    /**
     * The life of two users: created, modified, replaced, deactivated, deleted. The
     * resources and the patch are written as JSON here so that the SCIM payloads are easy
     * to read; an application would build them from its own objects.
     */
    void play() {
        try {
            while (isRunning() && !hasStream()) {
                Thread.sleep(200);
            }
            if (!isRunning()) {
                return;
            }
            LOG.info("The receiver registered its stream, the demo SCIM service provider starts");
            narrate("Alice is created at the service provider and joins the feed: feed:add and prov:create:full "
                    + "in one SET, with her representation as data");
            create(ALICE, "alice", json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "alice",
                      "displayName": "Alice Adams",
                      "emails": [{"type": "work", "value": "alice@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            narrate("Bob is created as well");
            create(BOB, "bob", json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "bob",
                      "displayName": "Bob Brown",
                      "emails": [{"type": "work", "value": "bob@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            narrate("Alice marries: a SCIM PATCH changes her display name and adds an email, prov:patch:full "
                    + "carries the PatchOp as data");
            patch(ALICE, json("""
                    {
                      "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                      "Operations": [
                        {"op": "replace", "path": "displayName", "value": "Alice Baker"},
                        {"op": "add", "path": "emails", "value": [
                          {"type": "work", "value": "alice@example.com"},
                          {"type": "home", "value": "alice.baker@home.example"}
                        ]}
                      ]
                    }
                    """), false);
            Thread.sleep(PAUSE);
            narrate("Bob is replaced wholesale with a SCIM PUT: prov:put:full carries the new representation");
            put(BOB, json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "robert",
                      "displayName": "Robert Brown",
                      "emails": [{"type": "work", "value": "robert@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            narrate("Alice's phone number changes, reported as a notice: prov:patch:notice names the attribute "
                    + "but carries no data, the receiver would have to GET the resource");
            patch(ALICE, json("""
                    {
                      "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                      "Operations": [
                        {"op": "replace", "path": "phoneNumbers", "value": [{"type": "work", "value": "+1 555 0100"}]}
                      ]
                    }
                    """), true);
            Thread.sleep(PAUSE);
            narrate("Alice leaves the company and is deactivated: prov:deactivate");
            setActive(ALICE, false);
            Thread.sleep(PAUSE);
            narrate("Bob is deleted: prov:delete, which also removes him from the feed");
            delete(BOB);
            LOG.info("The demo SCIM service provider is done, GET http://localhost:8083/users shows the result "
                    + "once the receiver has polled, GET /demo/scim/events the SETs");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IllegalStateException e) {
            LOG.info("The demo SCIM service provider stops: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return transmitter != null;
    }

    /**
     * Whether the receiver has registered its stream, which the transmitter needs for
     * the audience of the SETs.
     */
    public boolean hasStream() {
        TestTransmitter transmitter = DemoScimProvider.transmitter;
        return transmitter != null && !transmitter.streams().isEmpty();
    }

    public List<TransmittedSet> events() {
        return List.copyOf(sets);
    }

    /** {@code POST /Users}: {@code feed:add} and {@code prov:create:full} in one SET. */
    public TransmittedSet create(Map<String, Object> user) {
        String externalId = (user.get("externalId") instanceof String value) ? value
                : (user.get("userName") instanceof String value) ? value : null;
        return create(newId(), externalId, user);
    }

    /**
     * Creates the user: {@code feed:add} and {@code prov:create:full} in one SET, as RFC
     * 9967 allows for events about the same resource.
     */
    TransmittedSet create(String id, String externalId, Map<String, Object> user) {
        if (externalId != null) {
            externalIds.put(id, externalId);
        }
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.SCIM_FEED_ADD, Map.of());
        events.put(SsfEventTypes.SCIM_PROV_CREATE_FULL, Map.of("data", user, "version", nextVersion(id)));
        return transmit(id, events);
    }

    /** {@code PATCH /Users/{id}}: {@code prov:patch:full} with the {@code PatchOp} as data, or a notice. */
    public TransmittedSet patch(String id, Map<String, Object> patchOp, boolean notice) {
        String version = nextVersion(id);
        Map<String, Object> event = notice
                ? Map.of("attributes", attributesOf(patchOp), "version", version)
                : Map.of("data", patchOp, "version", version);
        return transmit(id, Map.of(notice ? SsfEventTypes.SCIM_PROV_PATCH_NOTICE : SsfEventTypes.SCIM_PROV_PATCH_FULL,
                event));
    }

    /** {@code PUT /Users/{id}}: {@code prov:put:full} with the new representation as data. */
    public TransmittedSet put(String id, Map<String, Object> user) {
        return transmit(id, Map.of(SsfEventTypes.SCIM_PROV_PUT_FULL, Map.of("data", user, "version", nextVersion(id))));
    }

    /** {@code DELETE /Users/{id}}: {@code prov:delete}. */
    public TransmittedSet delete(String id) {
        TransmittedSet set = transmit(id, Map.of(SsfEventTypes.SCIM_PROV_DELETE, Map.of()));
        versions.remove(id);
        externalIds.remove(id);
        return set;
    }

    /** {@code prov:activate} or {@code prov:deactivate}. */
    public TransmittedSet setActive(String id, boolean active) {
        return transmit(id, Map.of(active ? SsfEventTypes.SCIM_PROV_ACTIVATE : SsfEventTypes.SCIM_PROV_DEACTIVATE,
                Map.of("version", nextVersion(id))));
    }

    @SuppressWarnings("unchecked")
    private TransmittedSet transmit(String id, Map<String, Object> events) {
        TestTransmitter transmitter = DemoScimProvider.transmitter;
        if (transmitter == null) {
            throw new IllegalStateException("The demo SCIM service provider is not running");
        }
        if (transmitter.streams().isEmpty()) {
            throw new IllegalStateException("The receiver has not registered its stream yet, try again in a moment");
        }
        String uri = "/Users/" + id;
        // the receiver expects the audience the transmitter assigned to the stream
        List<String> audience = (List<String>) transmitter.streams().get(0).get("aud");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(transmitter.issuer())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .audience(audience)
                .claim("txn", newId())
                .claim("sub_id", SsfSubjectIdentifiers.scim(uri, externalIds.get(id)))
                .claim("events", events)
                .build();
        String jwt = transmitter.signSet(claims);
        transmitter.queueSet(jwt);
        TransmittedSet set = new TransmittedSet(jwt, claims.toJSONObject());
        sets.add(set);
        // the decoded SET in the log: this is what a SCIM Event looks like
        LOG.infof("Transmitted SET %s with %s for %s:%n%s", claims.getJWTID(),
                events.keySet().stream().map(SsfEventTypes::aliasOf).toList(), uri, pretty(set.claims()));
        return set;
    }

    /**
     * A headline in the log for the step that follows, to scroll along in a demo.
     */
    private static void narrate(String whatHappens) {
        // the line break leaves an empty line in the log before the headline
        LOG.infof("%n### %s", whatHappens);
    }

    private String nextVersion(String id) {
        return String.valueOf(versions.merge(id, 1, Integer::sum));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** The {@code path}s of a {@code PatchOp}, what a notice event reports as the changed attributes. */
    private static List<String> attributesOf(Map<String, Object> patchOp) {
        if (!(patchOp.get("Operations") instanceof List<?> operations)) {
            return List.of();
        }
        return operations.stream()
                .filter(Map.class::isInstance)
                .map(operation -> ((Map<?, ?>) operation).get("path"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .toList();
    }

    private Map<String, Object> json(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private String pretty(Map<String, Object> claims) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(claims);
        } catch (JsonProcessingException e) {
            return String.valueOf(claims);
        }
    }
}
