package io.quarkiverse.ssf.receiver.runtime.delivery.poll;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;

/**
 * Parser for the HTTP {@code Retry-After} header (RFC 9110 §10.2.3). The value
 * is either a non-negative integer number of seconds, or an HTTP-date in one of
 * the three formats recipients must accept (RFC 9110 §5.6.7): IMF-fixdate
 * ({@code Sun, 06 Nov 1994 08:49:37 GMT}), the obsolete RFC 850 form
 * ({@code Sunday, 06-Nov-94 08:49:37 GMT}) and asctime
 * ({@code Sun Nov  6 08:49:37 1994}).
 */
final class RetryAfter {

    private static final DateTimeFormatter ASCTIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("EEE MMM ppd HH:mm:ss yyyy")
            .toFormatter(Locale.US);

    private RetryAfter() {
    }

    /**
     * Converts a {@code Retry-After} value into a wait duration relative to
     * {@code now}. Returns empty when the value is {@code null}, blank or not in
     * any of the RFC 9110 forms. An HTTP-date in the past yields
     * {@link Duration#ZERO} — the wait has already elapsed.
     */
    static Optional<Duration> parse(String value, Instant now) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (isDigits(v)) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(v)));
            } catch (NumberFormatException overflow) {
                return Optional.of(Duration.ofSeconds(Long.MAX_VALUE));
            }
        }
        Instant at = parseHttpDate(v, now);
        if (at == null) {
            return Optional.empty();
        }
        Duration wait = Duration.between(now, at);
        return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static Instant parseHttpDate(String v, Instant now) {
        try {
            return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(v, rfc850Formatter(now)).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            // asctime carries no zone; RFC 9110 §5.6.7 fixes it to UTC.
            return LocalDateTime.parse(v, ASCTIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * RFC 850 dates use a two-digit year. RFC 9110 §5.6.7: a value that would be
     * more than 50 years in the future must be read as the most recent past year
     * with the same last two digits — i.e. resolve into the window
     * {@code [thisYear-50, thisYear+49]}. That window depends on {@code now}, so
     * the formatter is built per call.
     */
    private static DateTimeFormatter rfc850Formatter(Instant now) {
        int baseYear = now.atOffset(ZoneOffset.UTC).getYear() - 50;
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("EEEE, dd-MMM-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, baseYear)
                .appendPattern(" HH:mm:ss zzz")
                .toFormatter(Locale.US);
    }
}
