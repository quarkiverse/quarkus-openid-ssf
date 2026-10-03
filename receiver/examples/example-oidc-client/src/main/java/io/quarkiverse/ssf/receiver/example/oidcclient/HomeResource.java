package io.quarkiverse.ssf.receiver.example.oidcclient;

import java.time.Instant;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.quarkus.oidc.IdToken;

/**
 * The page a logged in user sees: the Keycloak session it belongs to, and a script that
 * notices when that session ends.
 */
@Path("/")
public class HomeResource {

    @Inject
    @IdToken
    JsonWebToken idToken;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String home() {
        String accountConsole = idToken.getIssuer() + "/account";
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>SSF example OIDC client</title></head>
                <body>
                <h1>Hello %s</h1>
                <table>
                <tr><td>Subject (sub)</td><td><code>%s</code></td></tr>
                <tr><td>Keycloak session (sid)</td><td><code>%s</code></td></tr>
                <tr><td>Logged in at</td><td><code>%s</code></td></tr>
                </table>
                <p>Sign out in the <a href="%s" target="_blank">Keycloak account console</a>: Keycloak reports
                the revoked session with a security event and the local session is gone. This page checks its
                session every few seconds and whenever the tab becomes visible, and sends you to the login
                once the session has ended.</p>
                <p id="status">Session check: pending</p>
                <p><a href="/logout">Log out here (and in Keycloak)</a></p>
                <script>
                (function () {
                    const status = document.getElementById("status");
                    let checks = 0;
                    function checkSession() {
                        // GET /auth/check answers 200 as long as the local session exists. Once the session
                        // was revoked the application sends the browser to the login instead, which fetch
                        // reports as a redirect (an opaque response) that the page treats as "session ended".
                        fetch("/auth/check", { headers: { "Accept": "application/json" }, cache: "no-store",
                                               credentials: "same-origin", redirect: "manual" })
                            .then(function (response) {
                                // 401/403, or a redirect to the login (an opaque response): the session is gone
                                if (response.type === "opaqueredirect" || response.status === 0
                                        || response.status === 401 || response.status === 403) {
                                    status.textContent = "Session check: session ended, redirecting to the login";
                                    window.location.reload();
                                    return;
                                }
                                if (response.status !== 200) {
                                    status.textContent = "Session check: failed with status " + response.status;
                                    return;
                                }
                                checks++;
                                status.textContent = "Session check " + checks + ": active at "
                                        + new Date().toLocaleTimeString();
                            })
                            .catch(function () {
                                status.textContent = "Session check: application not reachable";
                            });
                    }
                    document.addEventListener("visibilitychange", function () {
                        if (!document.hidden) {
                            checkSession();
                        }
                    });
                    setInterval(checkSession, 5000);
                    checkSession();
                })();
                </script>
                </body>
                </html>
                """.formatted(escape(idToken.getClaim("preferred_username")), escape(idToken.getSubject()),
                escape(idToken.getClaim("sid")), escape(Instant.ofEpochSecond(idToken.getIssuedAtTime())),
                escape(accountConsole));
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
