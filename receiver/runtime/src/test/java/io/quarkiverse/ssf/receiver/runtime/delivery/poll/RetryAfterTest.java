package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {

    /** Fixed "now": Sun, 06 Nov 1994 08:49:37 GMT — the RFC 9110 example timestamp. */
    private static final Instant NOW = Instant.parse("1994-11-06T08:49:37Z");

    @Test
    @DisplayName("delta-seconds → that many seconds")
    void deltaSeconds() {
        assertEquals(Optional.of(Duration.ofSeconds(120)), RetryAfter.parse("120", NOW));
        assertEquals(Optional.of(Duration.ZERO), RetryAfter.parse("0", NOW));
        // Surrounding whitespace is tolerated.
        assertEquals(Optional.of(Duration.ofSeconds(7)), RetryAfter.parse("  7 ", NOW));
    }

    @Test
    @DisplayName("Absurdly large delta-seconds doesn't throw — saturates (caller clamps anyway)")
    void hugeDeltaSaturates() {
        Optional<Duration> parsed = RetryAfter.parse("99999999999999999999999", NOW);
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().compareTo(Duration.ofDays(365)) > 0);
    }

    @Test
    @DisplayName("IMF-fixdate in the future → wait until then")
    void imfFixdate() {
        assertEquals(Optional.of(Duration.ofSeconds(23)),
                RetryAfter.parse("Sun, 06 Nov 1994 08:50:00 GMT", NOW));
    }

    @Test
    @DisplayName("IMF-fixdate in the past → zero wait, not negative")
    void imfFixdateInThePast() {
        assertEquals(Optional.of(Duration.ZERO),
                RetryAfter.parse("Sun, 06 Nov 1994 08:00:00 GMT", NOW));
    }

    @Test
    @DisplayName("Obsolete RFC 850 date is accepted (RFC 9110 §5.6.7)")
    void rfc850Date() {
        assertEquals(Optional.of(Duration.ofSeconds(23)),
                RetryAfter.parse("Sunday, 06-Nov-94 08:50:00 GMT", NOW));
    }

    @Test
    @DisplayName("RFC 850 two-digit year more than 50 years ahead is read as the past century")
    void rfc850TwoDigitYearWindow() {
        // From a 1994 vantage point, "60" must be 1960 (past), not 2060 (66 years ahead).
        assertEquals(Optional.of(Duration.ZERO),
                RetryAfter.parse("Sunday, 06-Nov-60 08:50:00 GMT", NOW));
        // ...while "40" is 2040 (46 years ahead) and yields a positive wait.
        Optional<Duration> future = RetryAfter.parse("Tuesday, 06-Nov-40 08:50:00 GMT", NOW);
        assertTrue(future.isPresent());
        assertTrue(future.get().compareTo(Duration.ofDays(365 * 40L)) > 0);
    }

    @Test
    @DisplayName("Obsolete asctime date is accepted and read as UTC (RFC 9110 §5.6.7)")
    void asctimeDate() {
        assertEquals(Optional.of(Duration.ofSeconds(23)),
                RetryAfter.parse("Sun Nov  6 08:50:00 1994", NOW));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "soon", "-5", "1.5", "Sun, 06 Nov 1994", "2026-08-28T12:00:00Z" })
    @DisplayName("Absent, blank or malformed values → empty (caller falls back to its own backoff)")
    void malformed(String value) {
        assertThat(RetryAfter.parse(value, NOW), is(equalTo(Optional.empty())));
    }
}
