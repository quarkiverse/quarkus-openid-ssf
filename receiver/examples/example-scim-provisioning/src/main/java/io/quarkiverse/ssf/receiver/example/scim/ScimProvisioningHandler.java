package io.quarkiverse.ssf.receiver.example.scim;

import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.scim.SsfScimEvent;
import org.easyssf.core.scim.SsfScimSubject;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.scim.SsfScimEventHandler;
import org.jboss.logging.Logger;

/**
 * Mirrors the SCIM Events (RFC 9967) about {@code Users} into the {@link UserDirectory}.
 * The receiver calls it for every verified SET like any {@code SsfEventHandler} bean;
 * {@link SsfScimEventHandler} dispatches the SCIM Events of the SET to the method of
 * their operation.
 *
 * <p>
 * {@code full} events carry the resource (or the patch) as {@code data} and are applied
 * directly. {@code notice} events only name the attributes that changed: the application
 * would fetch the resource from the SCIM service provider with a GET of the subject's
 * {@code uri}, which this example does not have, so it logs them. Like every handler it
 * is idempotent: applying an event twice leaves the directory in the same state.
 */
@Singleton
public class ScimProvisioningHandler extends SsfScimEventHandler {

    private static final Logger LOG = Logger.getLogger(ScimProvisioningHandler.class);

    private final UserDirectory directory;

    ScimProvisioningHandler(UserDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected void onScimEvent(SsfScimEvent event, SsfEventContext eventContext) {
        SsfScimSubject subject = event.subject();
        if (subject == null || subject.id() == null) {
            LOG.warnf("%s of SET %s has no SCIM subject, ignored", alias(event), eventContext.eventToken().jti());
            return;
        }
        if (!"Users".equals(subject.resourceType())) {
            LOG.infof("%s for %s: only Users are mirrored", alias(event), subject.uri());
            return;
        }
        super.onScimEvent(event, eventContext);
    }

    @Override
    protected void onFeedAdd(SsfScimEvent event, SsfEventContext eventContext) {
        LOG.infof("%s: %s joined the feed", alias(event), event.subject().uri());
    }

    @Override
    protected void onFeedRemove(SsfScimEvent event, SsfEventContext eventContext) {
        LOG.infof("%s: %s left the feed, the user stays in the directory", alias(event), event.subject().uri());
    }

    @Override
    protected void onCreate(SsfScimEvent event, SsfEventContext eventContext) {
        if (event.isFull()) {
            User user = User.fromScim(event.subject().id(), event.subject().externalId(), event.data(),
                    event.version());
            directory.save(user);
            LOG.infof("%s: created %s", alias(event), user);
        } else {
            logNotice(event, "created");
        }
    }

    @Override
    protected void onPut(SsfScimEvent event, SsfEventContext eventContext) {
        if (event.isFull()) {
            User user = User.fromScim(event.subject().id(), event.subject().externalId(), event.data(),
                    event.version());
            directory.save(user);
            LOG.infof("%s: replaced %s", alias(event), user);
        } else {
            logNotice(event, "replaced");
        }
    }

    @Override
    protected void onPatch(SsfScimEvent event, SsfEventContext eventContext) {
        if (!event.isFull()) {
            logNotice(event, "modified");
            return;
        }
        User patched = directory.update(event.subject().id(),
                user -> applyPatch(user, event.data()).withVersion(event.version()));
        if (patched != null) {
            LOG.infof("%s: patched %s", alias(event), patched);
        } else {
            LOG.warnf("%s: %s is not in the directory, nothing to patch", alias(event), event.subject().uri());
        }
    }

    @Override
    protected void onDelete(SsfScimEvent event, SsfEventContext eventContext) {
        User removed = directory.remove(event.subject().id());
        LOG.infof("%s: deleted %s", alias(event), (removed != null) ? removed : event.subject().uri());
    }

    @Override
    protected void onActivate(SsfScimEvent event, SsfEventContext eventContext) {
        setActive(event, true);
    }

    @Override
    protected void onDeactivate(SsfScimEvent event, SsfEventContext eventContext) {
        setActive(event, false);
    }

    private void setActive(SsfScimEvent event, boolean active) {
        User user = directory.update(event.subject().id(),
                existing -> existing.withActive(active).withVersion(event.version()));
        if (user != null) {
            LOG.infof("%s: %s %s", alias(event), active ? "activated" : "deactivated", user);
        } else {
            LOG.warnf("%s: %s is not in the directory", alias(event), event.subject().uri());
        }
    }

    /**
     * Applies the {@code Operations} of a SCIM {@code PatchOp} (RFC 7644, section 3.5.2)
     * to the user, as far as this application mirrors the attributes: {@code replace} and
     * {@code add} of a top-level attribute by {@code path}, or of several attributes with
     * a {@code value} object and no path; {@code remove} of {@code emails}.
     */
    @SuppressWarnings("unchecked")
    private static User applyPatch(User user, Map<String, Object> patch) {
        if (patch == null || !(patch.get("Operations") instanceof List<?> operations)) {
            return user;
        }
        for (Object element : operations) {
            if (!(element instanceof Map<?, ?> operation)) {
                continue;
            }
            String op = String.valueOf(operation.get("op")).toLowerCase();
            String path = (operation.get("path") instanceof String value) ? value : null;
            Object value = operation.get("value");
            switch (op) {
                case "add", "replace" -> {
                    if (path != null) {
                        user = user.with(path, value);
                    } else if (value instanceof Map<?, ?> attributes) {
                        for (Map.Entry<String, Object> attribute : ((Map<String, Object>) attributes).entrySet()) {
                            user = user.with(attribute.getKey(), attribute.getValue());
                        }
                    }
                }
                case "remove" -> {
                    if ("emails".equals(path)) {
                        user = user.with("emails", List.of());
                    } else {
                        LOG.debugf("remove of %s is not mirrored", path);
                    }
                }
                default -> LOG.debugf("unknown PatchOp operation %s", op);
            }
        }
        return user;
    }

    private static void logNotice(SsfScimEvent event, String what) {
        LOG.infof("%s: %s %s, changed %s; a notice event has no data, the application would GET the resource "
                + "from the SCIM service provider", alias(event), event.subject().uri(), what, event.attributes());
    }

    private static String alias(SsfScimEvent event) {
        return SsfEventTypes.aliasOf(event.eventType());
    }
}
