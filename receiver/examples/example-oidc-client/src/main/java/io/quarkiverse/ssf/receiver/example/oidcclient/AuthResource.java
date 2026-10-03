package io.quarkiverse.ssf.receiver.example.oidcclient;

import java.time.Instant;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.quarkus.oidc.IdToken;

/**
 * Lets the page find out whether its local session still exists. The check does not call
 * Keycloak: {@link SessionRevocation} fails the authentication once the Keycloak session
 * was revoked, so the request either arrives with a valid session (200) or is sent to the
 * login (a redirect, which the page treats as "session ended").
 */
@Path("/auth/check")
public class AuthResource {

    @Inject
    @IdToken
    JsonWebToken idToken;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response check() {
        // getClaim is generic: assign it before String.valueOf picks the char[] overload
        Object user = idToken.getClaim("preferred_username");
        Object sid = idToken.getClaim("sid");
        return Response.ok(Map.of(
                "user", String.valueOf(user),
                "sid", String.valueOf(sid),
                "checkedAt", Instant.now().toString()))
                .header("Cache-Control", "no-store")
                .build();
    }
}
