package io.quarkiverse.ssf.receiver.runtime.event;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.quarkiverse.ssf.receiver.runtime.SsfReceiverConfig;

/**
 * Pure unit tests for {@link SsfAliases}. Mocks {@link SsfReceiverConfig}
 * and invokes the package-private {@code init()} hook directly so the bean
 * can be exercised without a Quarkus boot.
 */
class SsfAliasesTest {

    private static final String VERIFICATION_URI = "https://schemas.openid.net/secevent/ssf/event-type/verification";
    private static final String STREAM_UPDATED_URI = "https://schemas.openid.net/secevent/ssf/event-type/stream-updated";
    private static final String SESSION_REVOKED_URI = "https://schemas.openid.net/secevent/caep/event-type/session-revoked";

    private static SsfAliases newAliases(SsfReceiverConfig config) {
        SsfAliases bean = new SsfAliases();
        bean.config = config;
        bean.init();
        return bean;
    }

    private static SsfReceiverConfig configWith(
            Map<String, URI> eventAliases,
            Map<String, URI> issuerAliases,
            Optional<String> alias,
            Optional<String> expectedAudience) {
        SsfReceiverConfig cfg = mock(SsfReceiverConfig.class);
        when(cfg.eventAliases()).thenReturn(eventAliases);
        when(cfg.issuerAliases()).thenReturn(issuerAliases);
        when(cfg.alias()).thenReturn(alias);
        when(cfg.expectedAudience()).thenReturn(expectedAudience);
        return cfg;
    }

    @Nested
    @DisplayName("event-type aliases")
    class EventTypeAliases {

        @Test
        @DisplayName("Built-in SSF spec aliases are present even with no config")
        void builtInsAreAlwaysPresent() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias(VERIFICATION_URI), equalTo("SsfStreamVerification"));
            assertThat(aliases.eventTypeAlias(STREAM_UPDATED_URI), equalTo("SsfStreamUpdated"));
        }

        @Test
        @DisplayName("Built-in CAEP-1.0 aliases are registered")
        void caepBuiltInsAreRegistered() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias(SESSION_REVOKED_URI),
                    equalTo("CaepSessionRevoked"));
            assertThat(aliases.eventTypeAlias(
                    "https://schemas.openid.net/secevent/caep/event-type/credential-change"),
                    equalTo("CaepCredentialChange"));
            assertThat(aliases.eventTypeAlias(
                    "https://schemas.openid.net/secevent/caep/event-type/risk-level-change"),
                    equalTo("CaepRiskLevelChange"));
        }

        @Test
        @DisplayName("Built-in RISC-1.0 aliases are registered")
        void riscBuiltInsAreRegistered() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias(
                    "https://schemas.openid.net/secevent/risc/event-type/account-purged"),
                    equalTo("RiscAccountPurged"));
            assertThat(aliases.eventTypeAlias(
                    "https://schemas.openid.net/secevent/risc/event-type/credential-compromise"),
                    equalTo("RiscCredentialCompromise"));
            assertThat(aliases.eventTypeAlias(
                    "https://schemas.openid.net/secevent/risc/event-type/opt-out-effective"),
                    equalTo("RiscOptOutEffective"));
        }

        @Test
        @DisplayName("Consumer config adds new aliases on top of built-ins")
        void consumerConfigOverlaysBuiltIns() {
            Map<String, URI> userAliases = new LinkedHashMap<>();
            userAliases.put("CaepSessionRevoked", URI.create(SESSION_REVOKED_URI));

            SsfAliases aliases = newAliases(configWith(
                    userAliases, Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            // Built-ins still there
            assertThat(aliases.eventTypeAlias(VERIFICATION_URI), equalTo("SsfStreamVerification"));
            // User entry resolved
            assertThat(aliases.eventTypeAlias(SESSION_REVOKED_URI), equalTo("CaepSessionRevoked"));
        }

        @Test
        @DisplayName("Consumer mapping for a built-in URI replaces the built-in alias name")
        void consumerOverridesBuiltInAliasName() {
            Map<String, URI> userAliases = new LinkedHashMap<>();
            userAliases.put("MyCustomVerificationName", URI.create(VERIFICATION_URI));

            SsfAliases aliases = newAliases(configWith(
                    userAliases, Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias(VERIFICATION_URI),
                    equalTo("MyCustomVerificationName"));
        }

        @Test
        @DisplayName("Unknown URI passes through unchanged")
        void unknownUriPassesThrough() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias("https://unknown.example/some/event"),
                    equalTo("https://unknown.example/some/event"));
        }

        @Test
        @DisplayName("null / blank URI returns 'unknown' so the metric tag stays non-empty")
        void nullOrBlankReturnsUnknown() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.eventTypeAlias(null), equalTo("unknown"));
            assertThat(aliases.eventTypeAlias(""), equalTo("unknown"));
            assertThat(aliases.eventTypeAlias("   "), equalTo("unknown"));
        }

        @Test
        @DisplayName("Entries with null alias / null URI are silently skipped")
        void invalidEntriesSkipped() {
            // Use a URI that has NO built-in alias so the passthrough is
            // observable. (session-revoked is now a CAEP-spec built-in.)
            String customUri = "https://example.org/custom/event-type/foo";
            // LinkedHashMap accepts null keys; the bean must not NPE on them.
            Map<String, URI> userAliases = new LinkedHashMap<>();
            userAliases.put(null, URI.create(customUri));
            userAliases.put("BlankKey", null);

            SsfAliases aliases = newAliases(configWith(
                    userAliases, Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            // Falls through to the URI itself — neither entry registered.
            assertThat(aliases.eventTypeAlias(customUri), equalTo(customUri));
        }

        @Test
        @DisplayName("eventTypeAliasesByUri() exposes the populated map for the Dev UI")
        void mapAccessorIsPopulated() {
            Map<String, URI> userAliases = new LinkedHashMap<>();
            userAliases.put("CaepSessionRevoked", URI.create(SESSION_REVOKED_URI));

            SsfAliases aliases = newAliases(configWith(
                    userAliases, Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            Map<String, String> map = aliases.eventTypeAliasesByUri();
            assertThat(map, hasEntry(VERIFICATION_URI, "SsfStreamVerification"));
            assertThat(map, hasEntry(STREAM_UPDATED_URI, "SsfStreamUpdated"));
            assertThat(map, hasEntry(SESSION_REVOKED_URI, "CaepSessionRevoked"));
        }
    }

    @Nested
    @DisplayName("issuer aliases")
    class IssuerAliases {

        @Test
        @DisplayName("Issuer aliases have NO built-in defaults")
        void noBuiltInIssuerAliases() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.issuerAliasesByUri(), is(Collections.emptyMap()));
        }

        @Test
        @DisplayName("Configured issuer aliases resolve correctly")
        void configuredIssuerAliasesResolve() {
            Map<String, URI> issuerAliases = new LinkedHashMap<>();
            issuerAliases.put("CaepDev", URI.create("https://ssf.caep.dev"));
            issuerAliases.put("KeycloakSsfPoc",
                    URI.create("https://kc.example.com/realms/ssf-poc"));

            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), issuerAliases,
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.issuerAlias("https://ssf.caep.dev"), equalTo("CaepDev"));
            assertThat(aliases.issuerAlias("https://kc.example.com/realms/ssf-poc"),
                    equalTo("KeycloakSsfPoc"));
        }

        @Test
        @DisplayName("Unknown issuer URL passes through unchanged")
        void unknownIssuerPassesThrough() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.issuerAlias("https://other.example/realms/r1"),
                    equalTo("https://other.example/realms/r1"));
        }

        @Test
        @DisplayName("Built-in event-type aliases don't bleed into the issuer table")
        void domainsAreIndependent() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            // Verification URI is a registered event-type alias but NOT an issuer alias.
            assertThat(aliases.issuerAliasesByUri(), not(hasKey(VERIFICATION_URI)));
            // Asking for it as an issuer falls through to the URI itself.
            assertThat(aliases.issuerAlias(VERIFICATION_URI), equalTo(VERIFICATION_URI));
        }
    }

    @Nested
    @DisplayName("receiver alias precedence")
    class ReceiverAliasPrecedence {

        @Test
        @DisplayName("Explicit quarkus.openid-ssf.receiver.alias wins over expected-audience")
        void explicitAliasWins() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.of("my-receiver"),
                    Optional.of("https://my-app.example")));

            assertThat(aliases.receiverAlias(), equalTo("my-receiver"));
        }

        @Test
        @DisplayName("Falls back to expected-audience when alias is unset")
        void fallsBackToExpectedAudience() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(),
                    Optional.of("https://my-app.example")));

            assertThat(aliases.receiverAlias(), equalTo("https://my-app.example"));
        }

        @Test
        @DisplayName("Falls back to 'unknown' when both are unset / blank")
        void fallsBackToUnknown() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.receiverAlias(), equalTo("unknown"));
        }

        @Test
        @DisplayName("Blank alias is ignored (skips to next fallback)")
        void blankAliasIsIgnored() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.of("   "),
                    Optional.of("https://my-app.example")));

            assertThat(aliases.receiverAlias(), equalTo("https://my-app.example"));
        }
    }

    @Nested
    @DisplayName("resolveEventTypeRef")
    class ResolveEventTypeRef {

        @Test
        @DisplayName("Built-in alias resolves to its URI")
        void builtInAlias() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.resolveEventTypeRef("CaepSessionRevoked"),
                    equalTo(URI.create(SESSION_REVOKED_URI)));
            assertThat(aliases.resolveEventTypeRef("RiscAccountDisabled"),
                    equalTo(URI.create("https://schemas.openid.net/secevent/risc/event-type/account-disabled")));
        }

        @Test
        @DisplayName("Consumer-configured alias resolves to its URI")
        void consumerConfiguredAlias() {
            String customUri = "https://schemas.example.org/vendor/event-type/foo";
            Map<String, URI> userAliases = new LinkedHashMap<>();
            userAliases.put("VendorFoo", URI.create(customUri));

            SsfAliases aliases = newAliases(configWith(
                    userAliases, Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            assertThat(aliases.resolveEventTypeRef("VendorFoo"), equalTo(URI.create(customUri)));
        }

        @Test
        @DisplayName("Full URI passes through verbatim")
        void uriPassesThrough() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            String customUri = "https://schemas.example.org/vendor/event-type/never-aliased";
            assertThat(aliases.resolveEventTypeRef(customUri), equalTo(URI.create(customUri)));
        }

        @Test
        @DisplayName("Unknown alias throws IllegalArgumentException naming the registered set")
        void unknownAliasThrows() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            IllegalArgumentException ex = org.junit.jupiter.api.Assertions
                    .assertThrows(IllegalArgumentException.class,
                            () -> aliases.resolveEventTypeRef("CaepSesssionRevoked")); // typo: extra 's'

            // Message points at the bad input AND lists the registered names
            // so the operator can spot the typo.
            assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("CaepSesssionRevoked"));
            assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("CaepSessionRevoked"));
        }

        @Test
        @DisplayName("Blank / null input throws IllegalArgumentException")
        void blankInputThrows() {
            SsfAliases aliases = newAliases(configWith(
                    Collections.emptyMap(), Collections.emptyMap(),
                    Optional.empty(), Optional.empty()));

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> aliases.resolveEventTypeRef(null));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> aliases.resolveEventTypeRef("   "));
        }
    }
}
