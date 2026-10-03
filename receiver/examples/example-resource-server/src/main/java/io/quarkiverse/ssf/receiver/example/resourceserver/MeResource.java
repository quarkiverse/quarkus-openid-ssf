package io.quarkiverse.ssf.receiver.example.resourceserver;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The API: needs an access token issued by Keycloak, which {@link TokenRevocation}
 * rejects once its session was revoked.
 */
@Path("/api/me")
public class MeResource {

    @Inject
    JsonWebToken accessToken;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> me() {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", accessToken.getClaim("preferred_username"));
        me.put("subject", accessToken.getSubject());
        me.put("sessionId", accessToken.getClaim("sid"));
        me.put("tokenIssuedAt", String.valueOf(Instant.ofEpochSecond(accessToken.getIssuedAtTime())));
        me.put("tokenExpiresAt", String.valueOf(Instant.ofEpochSecond(accessToken.getExpirationTime())));
        return me;
    }
}
