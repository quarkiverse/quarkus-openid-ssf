# Examples

Five applications that use the `quarkus-openid-ssf-receiver` extension.

| | Port | |
|---|---|---|
| [`keycloak/`](keycloak) | 8080 | Docker Compose setup: Keycloak 26.8 with the `ssf` feature and a pre-configured `ssf-demo` realm, for the two applications below. The same setup as the one of the [easyssf Spring Boot examples](https://github.com/easyssf/easyssf). |
| [`example-resource-server/`](example-resource-server) | 8081 | OAuth2 resource server (`quarkus-oidc`). Rejects access tokens once their Keycloak session was revoked. Has a `poll` profile, metrics and a health check. |
| [`example-oidc-client/`](example-oidc-client) | 8082 | Web application with OIDC login (`quarkus-oidc`). Ends the local session once the Keycloak session was revoked or the user's credentials changed. |
| [`example-receiver-managed-stream/`](example-receiver-managed-stream) | 28080 | Developer aid without login: exposes what it received and the stream management API over REST. Profiles for [caep.dev](https://ssf.caep.dev) and Keycloak, PUSH and POLL. |
| [`example-transmitter-managed-stream/`](example-transmitter-managed-stream) | 28080 | Like the one above, for a stream an operator created at the transmitter (`stream-management=TRANSMITTER` with a `stream-id`). |
| [`example-scim-provisioning/`](example-scim-provisioning) | 8083 | Mirrors SCIM `Users` into a local directory from SCIM Events (RFC 9967). Runs without Keycloak, see [5.](#5-scim-provisioning) |

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
the store. The handler needs a subject with a `sid` or `sub`: for a SET with a `scim` subject
(RFC 9967, see [5.](#5-scim-provisioning)) it logs a warning and revokes nothing, which is right
for a resource server that validates access tokens.

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

## 5. SCIM provisioning

Keycloak does not emit SCIM Events (RFC 9967), so this example does not need it. The application
starts a demo SCIM service provider over easyssf's `TestTransmitter` and points its receiver at it.
Once the receiver has registered its stream, the demo tells the life of two users, one SET every
few seconds, and logs every SET decoded; the receiver polls the SETs and mirrors the users into a
local directory (`/users`) with a handler that extends `SsfScimEventHandler`.

```sh
mvn -pl receiver/examples/example-scim-provisioning quarkus:dev
```

```
Demo SCIM service provider transmits at http://127.0.0.1:52174/realms/test
Created SSF stream 3f0d…  (delivery urn:ietf:rfc:8936 http://127.0.0.1:52174/realms/test/poll, events [ScimFeedAdd, …], audience [receiver/3f0d…])
The receiver registered its stream, the demo SCIM service provider starts

### Alice is created at the service provider and joins the feed: feed:add and prov:create:full in one SET, with her representation as data
Transmitted SET 2c6a… with [ScimFeedAdd, ScimProvCreateFull] for /Users/2b2f880af6674ac284bae9381673d462:
{
  "iss" : "http://127.0.0.1:52174/realms/test",
  "sub_id" : { "format" : "scim", "uri" : "/Users/2b2f880af6674ac284bae9381673d462", "externalId" : "alice" },
  "events" : { "urn:ietf:params:scim:event:feed:add" : { }, "urn:ietf:params:scim:event:prov:create:full" : { … } },
  …
}
ScimFeedAdd: /Users/2b2f880af6674ac284bae9381673d462 joined the feed
ScimProvCreateFull: created User[id=2b2f880af6674ac284bae9381673d462, externalId=alice, userName=alice, displayName=Alice Adams, emails=[alice@example.com], active=true, version=1]
ScimProvPatchFull: patched User[…, displayName=Alice Baker, emails=[alice@example.com, alice.baker@home.example], …, version=2]
ScimProvPatchNotice: /Users/2b2f880a… modified, changed [phoneNumbers]; a notice event has no data, the application would GET the resource from the SCIM service provider
ScimProvDeactivate: deactivated User[…, active=false, version=4]
ScimProvDelete: deleted User[id=c3a6e1f0…, userName=robert, …]
```

```sh
curl -s localhost:8083/users | jq
```

### Walk through it yourself

The demo service provider is a tiny stand-in for the `/Users` endpoint of a SCIM server. Every
request to `/demo/scim/Users` transmits the SCIM Event about the change, the receiver mirrors it
on its next poll. Start with `-Ddemo.autoplay=false` to begin with an empty directory. Create,
patch, deactivate and delete a user at the service provider and watch the directory:

```sh
APP=http://localhost:8083

# create: feed:add + prov:create:full in one SET, the response is the SET as transmitted
CAROL=$(curl -s -X POST $APP/demo/scim/Users -H 'Content-Type: application/json' \
  -d '{"externalId":"carol","userName":"carol","name":{"givenName":"Carol","familyName":"Clark"},"emails":[{"value":"carol@example.com"}]}' \
  | jq -r '.claims.sub_id.uri | split("/") | last')
sleep 2; curl -s $APP/users/$CAROL | jq

# patch: prov:patch:full carries the PatchOp as data
curl -s -X PATCH $APP/demo/scim/Users/$CAROL -H 'Content-Type: application/json' \
  -d '{"Operations":[{"op":"replace","path":"displayName","value":"Carol Clark-Davis"}]}' > /dev/null
sleep 2; curl -s $APP/users/$CAROL | jq .displayName

# a notice names the changed attributes but carries no data: logged, the directory is unchanged
curl -s -X PATCH "$APP/demo/scim/Users/$CAROL?mode=notice" -H 'Content-Type: application/json' \
  -d '{"Operations":[{"op":"replace","path":"phoneNumbers","value":[{"value":"+1 555 0100"}]}]}' > /dev/null

# deactivate, activate, replace, delete
curl -s -X POST $APP/demo/scim/Users/$CAROL/deactivate > /dev/null; sleep 2; curl -s $APP/users/$CAROL | jq .active
curl -s -X POST $APP/demo/scim/Users/$CAROL/activate > /dev/null
curl -s -X PUT $APP/demo/scim/Users/$CAROL -H 'Content-Type: application/json' \
  -d '{"userName":"cdavis","displayName":"Carol Davis","emails":[{"value":"carol.davis@example.com"}],"active":true}' > /dev/null
curl -s -X DELETE $APP/demo/scim/Users/$CAROL > /dev/null; sleep 2; curl -i -s $APP/users/$CAROL | head -1    # 404

# the SETs the demo service provider transmitted, decoded
curl -s $APP/demo/scim/events | jq
```

[`example-scim-provisioning.http`](example-scim-provisioning/example-scim-provisioning.http) runs
the same life of a user with the HTTP client of IntelliJ IDEA, checking the directory after each
step; the resource server and OIDC client examples have such files too.

What is specific to SCIM Events is the subject, a `scim` identifier with the relative `uri` of the
resource and its `externalId`, and the event types under `urn:ietf:params:scim:event:`; the SET is
verified like any other. `ScimProvisioningHandler` extends easyssf's `SsfScimEventHandler`, which
hands every SCIM Event of a SET to the method of its operation (`onCreate`, `onPatch`, `onDelete`,
…) as an `SsfScimEvent`: the resource as `data()` of a `full` event, the changed `attributes()` of
a `notice` event, the ETag `version()`. `full` events are applied to the directory, `notice`
events are logged, as the example has no SCIM service provider to fetch the resource from.

### Tests

`ScimProvisioningTest` plays the life of a user through the `TestTransmitter` itself, the demo
service provider and its autoplay are not running in the tests; `ScimProvisioningIT` runs it against the native binary:

```sh
mvn -pl receiver/examples/example-scim-provisioning verify -Pnative
```

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
