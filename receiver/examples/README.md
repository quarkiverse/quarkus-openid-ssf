# Examples

Four applications that use the `quarkus-openid-ssf-receiver` extension.

| | Port | |
|---|---|---|
| [`keycloak/`](keycloak) | 8080 | Docker Compose setup: Keycloak 26.8 with the `ssf` feature and a pre-configured `ssf-demo` realm, for the two applications below. The same setup as the one of the [easyssf Spring Boot examples](https://github.com/easyssf/easyssf). |
| [`example-resource-server/`](example-resource-server) | 8081 | OAuth2 resource server (`quarkus-oidc`). Rejects access tokens once their Keycloak session was revoked. Has a `poll` profile, metrics and a health check. |
| [`example-oidc-client/`](example-oidc-client) | 8082 | Web application with OIDC login (`quarkus-oidc`). Ends the local session once the Keycloak session was revoked or the user's credentials changed. |
| [`example-receiver-managed-stream/`](example-receiver-managed-stream) | 28080 | Developer aid without login: exposes what it received and the stream management API over REST. Profiles for [caep.dev](https://ssf.caep.dev) and Keycloak, PUSH and POLL. |
| [`example-transmitter-managed-stream/`](example-transmitter-managed-stream) | 28080 | Like the one above, for a stream an operator created at the transmitter (`stream-management=TRANSMITTER` with a `stream-id`). |

The resource server and the OIDC client contain no SSF specific code apart from configuration, a
few lines that reject revoked tokens (a `SecurityIdentityAugmentor` in the resource server, a
`TokenStateManager` in the OIDC client), and an optional `SsfEventHandler` that logs the events. Both switch proactive authentication off (`quarkus.http.auth.proactive=false`), so
that `quarkus-oidc` does not take the `Authorization` header Keycloak sends to the push endpoint
for an access token. Both manage their own stream: on startup they authenticate with the service
account of their Keycloak client and register a PUSH stream for it (`Created SSF stream …`), after
a restart they find it again (`Using existing SSF stream …`). If you delete a stream in the admin
console, the application creates a new one on its next start.

IntelliJ IDEA users find run configurations for every application and delivery method in
`.idea/runConfigurations` (`example-resource-server (PUSH)`, `example-resource-server (POLL)`,
`example-oidc-client`, …).

## 1. Start Keycloak

```sh
cd receiver/examples/keycloak
docker compose up
```

- Admin console: <http://localhost:8080> (`admin` / `admin`), realm `ssf-demo`
- Users: `tester` / `test` and `bob` / `bob`

## 2. Build

In the root of the repository:

```sh
mvn install -DskipTests
```

## 3. Resource server

```sh
mvn -pl receiver/examples/example-resource-server quarkus:dev
```

Run the requests in
[`example-resource-server.http`](example-resource-server/example-resource-server.http) with the
HTTP client of IntelliJ IDEA, or use `curl`:

```sh
KC=http://localhost:8080/realms/ssf-demo/protocol/openid-connect

# log in as tester and call the API
TOKENS=$(curl -s $KC/token -d grant_type=password -d client_id=example-cli -d username=tester -d password=test)
ACCESS_TOKEN=$(echo "$TOKENS" | jq -r .access_token)
REFRESH_TOKEN=$(echo "$TOKENS" | jq -r .refresh_token)

curl -i localhost:8081/api/me -H "Authorization: Bearer $ACCESS_TOKEN"    # 200

# end the session in Keycloak ...
curl $KC/logout -d client_id=example-cli -d refresh_token=$REFRESH_TOKEN

# ... the access token has not expired, but is rejected within a few seconds
curl -i localhost:8081/api/me -H "Authorization: Bearer $ACCESS_TOKEN"    # 401
```

The log of the resource server shows the stream it registered on startup and the event:

```
Created SSF stream d0200637-… (delivery urn:ietf:rfc:8935 http://host.docker.internal:8081/ssf/push, events [CaepSessionRevoked], audience [example-resource-server/d0200637-…])
Received SET b639c4e1-… with events [CaepSessionRevoked]
Security event CaepSessionRevoked for user 33bf4f70-… and session hPB29fvO…
CaepSessionRevoked: revoked access tokens of session hPB29fvO…
```

How it works: `TokenRevocation` produces easyssf's `SsfTokenRevocationEventHandler`, which records
the session of every `CaepSessionRevoked` event in an `SsfTokenRevocationStore`, and implements a
`SecurityIdentityAugmentor` that fails the authentication of an access token whose session is in
the store.

### Metrics and health

The resource server has `quarkus-micrometer-registry-prometheus` and `quarkus-smallrye-health`,
so the receiver records what it does and reports whether it is in contact with the transmitter:

```sh
curl -s localhost:8081/q/metrics | grep ^easyssf_receiver
curl -s localhost:8081/q/health/well | jq
```

### POLL delivery

With the profile `poll` the resource server does not register a PUSH stream for its own client.
It authenticates with the service account of the client `example-poll-receiver` instead, registers
a POLL stream for it and polls Keycloak for events every two seconds, see the `%poll.` settings in
[`application.properties`](example-resource-server/src/main/resources/application.properties):

```sh
mvn -pl receiver/examples/example-resource-server quarkus:dev -Dquarkus.profile=poll
```

```
Created SSF stream ef50c4f3-… (delivery urn:ietf:rfc:8936 http://localhost:8080/…/poll, events [CaepSessionRevoked], audience [example-poll-receiver/ef50c4f3-…])
Received SET 52e22461-… (POLL) with events [CaepSessionRevoked]
```

The `curl` commands and the `.http` file work the same way. Keycloak keeps pushing to the PUSH
stream of `example-resource-server` in the meantime (streams survive a restart of the
application), which the application rejects in this profile.

### Tests

`ResourceServerTest` runs the scenario above against easyssf's in-process `TestTransmitter`, which
stands in for Keycloak (it issues the access tokens and signs the SETs). `ResourceServerIT` runs the
same test against the native binary:

```sh
mvn -pl receiver/examples/example-resource-server verify -Pnative
```

## 4. OIDC client

```sh
mvn -pl receiver/examples/example-oidc-client quarkus:dev
```

1. Open <http://localhost:8082> and log in as `tester` / `test`. The page shows the Keycloak
   session (`sid`).
2. Sign out in Keycloak, not in the application: open the
   [account console](http://localhost:8080/realms/ssf-demo/account) (linked on the page) and sign
   out.
3. Switch back to the tab of the application: the page notices that its session is gone and
   redirects to the login.

The page polls `GET /auth/check` every five seconds and whenever its tab becomes visible. The
endpoint does not call Keycloak: `RevocationAwareTokenStateManager`, a `TokenStateManager` around
the default one of `quarkus-oidc`, refuses the tokens of the session cookie once the
`session-revoked` event arrived (`SessionRevocation` produces the easyssf event handler and the
store it checks). `quarkus-oidc` then removes the cookie and sends the browser to the login, the
same way it ends a session on a back-channel logout; the check answers 200 while the session
exists.

The client `example-oidc-client` has neither a back-channel nor a front-channel logout URL in
Keycloak. The application learns about the logout only through the `session-revoked` event, which
names the Keycloak session:

```
Received SET c7e34991-… with events [CaepSessionRevoked]
CaepSessionRevoked: revoked access tokens of session QkNy7B8GqiyQ4nf92kutVw4w
Ending the local session of 33bf4f70-…: Keycloak session QkNy7B8GqiyQ4nf92kutVw4w was revoked
```

Other things to try:

- In the admin console, sign the user out of all sessions or reset the password: all local
  sessions of the user end (`session-revoked` for the user, `credential-change`).
- *Log out here* in the application logs the user out of Keycloak as well (RP-initiated logout),
  Keycloak then sends a `session-revoked` event for that session to both applications.

## What triggers an event in Keycloak

| Action | Event | Subject |
|---|---|---|
| A user logs out (logout in an application, `/logout` endpoint) | `session-revoked` | user and session |
| An admin signs a user out of all sessions | `session-revoked` | user, session `ALL` |
| A credential changes (for example an admin resets the password) | `credential-change` | user |

Removing a *single* session through the admin API (`DELETE /admin/realms/{realm}/sessions/{id}`)
does not produce an event in Keycloak 26.8.

## How the realm is set up

Everything is in [`keycloak/ssf-demo-realm.json`](keycloak/ssf-demo-realm.json) and
[`keycloak/compose.yaml`](keycloak/compose.yaml):

| | |
|---|---|
| `--features=ssf` | SSF is an experimental feature of Keycloak. |
| Realm attribute `ssf.transmitterEnabled` | Turns the realm into an SSF transmitter. |
| Clients `example-resource-server` and `example-oidc-client` | SSF receivers (`ssf.enabled`). A receiver is a client, so the resource server gets one too although it does not log users in. |
| Service account and optional client scopes `ssf.read`, `ssf.manage` | Let the application manage the stream of its client (`stream-management=RECEIVER` with `oauth2.*`). Keycloak creates the two client scopes when the `ssf` feature is enabled. |
| Client attribute `ssf.defaultSubjects=ALL` | Deliver events of all users. By default a receiver only gets events of users that were subscribed explicitly. |
| Client attribute `ssf.validPushUrls` | The push URLs a stream of this client may use, here `http://host.docker.internal:<port>/ssf/push`, which the application registers (`push.delivery-endpoint-url`). |
| Client attributes `ssf.stream.*` | The stream itself. Not in the realm file: the application creates it on startup, with the `Authorization` header from `push.expected-auth-header`. Keycloak assigns the audience (`<clientId>/<streamId>`), which the application takes from the stream to validate SETs. In the admin console: *Clients* → client → *SSF*. |
| Client `example-poll-receiver` | Receiver for the `poll` profile of the resource server: a second client, because Keycloak allows one stream per client and the PUSH stream of `example-resource-server` stays. |
| Client `example-cli` | Public client to obtain access tokens with `curl`. |
| `allow-insecure-push-targets=true` | Keycloak only pushes to public `https` URLs by default. |
| `outbox-drainer-interval=2s` | Keycloak delivers events every 30 seconds by default. |
| `KC_HOSTNAME=http://localhost:8080` | Fixes the issuer, events are created outside of a request. |

Keycloak stores its database in `keycloak/data`, so streams, users and changes made in the admin
console survive `docker compose down`. The realm is only imported into an empty database: to pick
up changes to `ssf-demo-realm.json`, stop Keycloak and delete `keycloak/data`.

## Using another Keycloak

Both applications read the issuer from `KEYCLOAK_ISSUER` (default
`http://localhost:8080/realms/ssf-demo`), the secret of their own client from
`KEYCLOAK_CLIENT_SECRET` (the `poll` profile reads the secret of `example-poll-receiver` from
`KEYCLOAK_POLL_CLIENT_SECRET`) and the URL under which Keycloak reaches their push endpoint from
`SSF_PUSH_URL` (default `http://host.docker.internal:<port>/ssf/push`). The clients need a service
account with the client scopes `ssf.read` and `ssf.manage`, and the push URL has to be allowed in
`ssf.validPushUrls` of the client. The push authorization header is set in the
`application.properties` of each application. An issuer that is not `localhost` has to use
`https`, or `quarkus.openid-ssf.receiver.allow-insecure-http=true` for a test setup.
