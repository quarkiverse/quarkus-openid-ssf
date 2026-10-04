package io.quarkiverse.ssf.receiver.example.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A user of the local directory, the mirror of a SCIM {@code User} resource (RFC 7643,
 * section 4.1): the attributes this application cares about, and the ETag {@code version}
 * the service provider reported with the last event about the resource.
 */
public record User(String id, String externalId, String userName, String displayName, List<String> emails,
        boolean active, String version) {

    public User {
        emails = List.copyOf(emails);
    }

    /**
     * Builds the user from the {@code data} of a {@code prov:create:full} or
     * {@code prov:put:full} event, the representation of the resource.
     */
    static User fromScim(String id, String externalId, Map<String, Object> data, String version) {
        return new User(id, externalId, string(data.get("userName")), displayName(data), emails(data.get("emails")),
                !Boolean.FALSE.equals(data.get("active")), version);
    }

    /**
     * Applies one attribute of a SCIM PATCH ({@code replace} or {@code add}) to this
     * user. Attributes this application does not mirror are ignored.
     */
    User with(String attribute, Object value) {
        return switch (attribute) {
            case "userName" -> new User(id, externalId, string(value), displayName, emails, active, version);
            case "displayName" -> new User(id, externalId, userName, string(value), emails, active, version);
            case "emails" -> new User(id, externalId, userName, displayName, emails(value), active, version);
            case "active" -> withActive(!Boolean.FALSE.equals(value) && !"false".equals(value));
            default -> this;
        };
    }

    User withActive(boolean active) {
        return new User(id, externalId, userName, displayName, emails, active, version);
    }

    User withVersion(String version) {
        return (version != null) ? new User(id, externalId, userName, displayName, emails, active, version) : this;
    }

    private static String displayName(Map<String, Object> data) {
        String displayName = string(data.get("displayName"));
        if (displayName != null) {
            return displayName;
        }
        if (data.get("name") instanceof Map<?, ?> name) {
            String formatted = string(name.get("formatted"));
            if (formatted != null) {
                return formatted;
            }
            String given = string(name.get("givenName"));
            String family = string(name.get("familyName"));
            if (given != null || family != null) {
                return ((given != null) ? given + " " : "") + ((family != null) ? family : "");
            }
        }
        return null;
    }

    /**
     * The values of the multi-valued {@code emails} attribute: complex values with a
     * {@code value} member, or plain strings.
     */
    private static List<String> emails(Object value) {
        List<String> emails = new ArrayList<>();
        if (value instanceof List<?> values) {
            for (Object email : values) {
                if (email instanceof Map<?, ?> complex && complex.get("value") instanceof String address) {
                    emails.add(address);
                } else if (email instanceof String address) {
                    emails.add(address);
                }
            }
        }
        return emails;
    }

    private static String string(Object value) {
        return (value instanceof String string && !string.isBlank()) ? string : null;
    }
}
