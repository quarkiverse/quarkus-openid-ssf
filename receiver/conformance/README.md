# Conformance tests

Runs the SSF receiver built on easyssf, hosted in Quarkus, against the
[OpenID conformance suite](https://gitlab.com/openid/conformance-suite). The module is the
Quarkus counterpart of easyssf's `easyssf-receiver-spring-boot-conformance-tests` and consists of

- the **receiver under test** (`io.quarkiverse.ssf.receiver.conformance`): a small Quarkus
  application that acts as the SSF receiver the suite tests with its
  `openid-ssf-receiver-caep-test-plan` and `openid-ssf-receiver-test-plan`. The suite emulates a
  transmitter and an authorization server; the application plays the stream operations the tests
  expect and accepts the events they deliver. Every test of the suite emulates a transmitter with new
  signing keys under the same issuer, so every test run gets a fresh receiver assembled from
  `easyssf-receiver` (see `ConformanceRun` in `easyssf-test-conformance`): token, verifier,
  de-duplication and stream are per run. The `quarkus-openid-ssf-receiver` extension is not
  involved, as easyssf's Spring Boot starter is not in its own conformance module: what the suite
  certifies is the protocol implementation both share.
- the **automated conformance tests**: four classes of a few lines that run the plans of
  `org.easyssf:easyssf-test-conformance` against that application. The library starts the suite,
  creates the test plans, runs every module and asserts the result the suite reports, and holds the
  scenarios the receiver plays, so this module only hosts the receiver and extends the same test the
  Spring Boot module does, see [Automated tests](#automated-tests). The receiver can also be run by
  hand against a suite, see [Manual runs](#manual-runs).

## Automated tests

The tests are tagged `conformance` and skipped by the normal build (which still runs
`ConformanceRunLocalTransmitterTest`, the scenarios against easyssf's in-process test
transmitter, without Docker). The `conformance` profile runs only the tagged tests; a plan takes
a few minutes, as each module waits for the receiver to deliver, verify and fall idle.

```sh
# all four plans (default and CAEP interop profile, push and poll delivery)
mvn -pl receiver/conformance -Pconformance verify
# one plan
mvn -pl receiver/conformance -Pconformance verify -Dtest=CaepInterop10PushReceiverConformanceTest
```

| Test | Plan | Variant | Suite configuration (in `easyssf-test-conformance`) |
|---|---|---|---|
| `Ssf10PushReceiverConformanceTest` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=push` | `suite-config/receiver-ssf1.0-push.json` |
| `Ssf10PollReceiverConformanceTest` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=poll` | `suite-config/receiver-ssf1.0-poll.json` |
| `CaepInterop10PushReceiverConformanceTest` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=push` | `suite-config/receiver-ssf1.0-caepiop1.0-push.json` |
| `CaepInterop10PollReceiverConformanceTest` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=poll` | `suite-config/receiver-ssf1.0-caepiop1.0-poll.json` |

All plans use `ssf_auth_mode=dynamic` and `client_auth_type=client_secret_basic`. A test class
names its `ConformancePlan`, the library creates it in the suite and runs one parameterized test
per module of the plan, so the modules follow the suite version in use. `ReceiverModules` maps a
module to the scenario the receiver plays (see [Scenarios](#scenarios)) and the status its run has
to end with; a failed module fails with the failures and warnings the suite logged, the receiver's
log and the link to the suite's log page.

How the Quarkus side is wired: `ConformanceSuiteResource`, a `QuarkusTestResourceLifecycleManager`,
starts the suite before the application and hands it the suite's TLS certificate (as the trust
store of the `conformance-suite` TLS configuration), the push URL under which the suite reaches
the receiver and the port. The application serves HTTPS on that port with the certificate of
`easyssf-test-conformance` (`tls/receiver.pem`, issued for `localhost`,
`host.testcontainers.internal` and `host.docker.internal`), see
`src/test/resources/application.properties`. The four plan tests share one application and one
suite: `@WithTestResource(scope = MATCHING_RESOURCES)` keeps Quarkus from restarting between them.

### The suite

The suite is started with [Testcontainers](https://testcontainers.com) (MongoDB, the suite and its
nginx, like `docker-compose-prebuilt.yml` of the suite's repository), so Docker is all that is
needed. The suite has to know its own URL before it starts, so nginx is bound to a fixed host port
(`https://localhost:18443`, where the suite's UI shows the plans while the tests run), and the suite
reaches the receiver on the Docker host as `host.testcontainers.internal`.

| System property / environment variable | Default | |
|---|---|---|
| `cts.suite.image` / `CTS_SUITE_IMAGE` | `registry.gitlab.com/openid/conformance-suite:latest` (the suite's current master build, see below) | the suite image |
| `cts.suite.nginx-image` | `registry.gitlab.com/openid/conformance-suite/nginx:latest` | its nginx image |
| `cts.suite.mongodb-image` | `mongo@sha256:…` (`9.0.2-noble`, pinned by digest; the suite itself runs 6.0.13 in production) | its MongoDB image |
| `cts.suite.port` | `18443` | host port of the Testcontainers-started suite |
| `cts.receiver.port` | `9444` | port of the receiver under test |
| `cts.receiver.push-url` | `https://<host as seen from the suite>:<receiver port>/ssf/push` | the push URL the receiver registers |

The properties can be given to Maven (`-Dcts.suite.image=...`), which passes them on to the tests.
The defaults live in `ConformanceSettings` of `easyssf-test-conformance`, so this module follows
easyssf when the suite images change.

**Which suite.** The tests run the suite's current `latest` build to work around issues in the SSF
tests of the last release (`release-v5.3.1`: fewer modules, and a race in its emulated transmitter
that fails most poll modules). To run a build of your own, build the image from a checkout like
the suite's CI does (`.gitlab-ci.yml`, job `build-image`: `mvn package`, then
`docker build -t conformance-suite .`) and name it; Testcontainers uses images of the local Docker
daemon as they are:

```sh
mvn -pl receiver/conformance -Pconformance verify -Dcts.suite.image=conformance-suite:master-949724883
```

The nginx image is independent of the suite version; the released one does for a locally built
suite.

**CI.** The `Conformance` workflow (`.github/workflows/conformance.yml`) runs the four plans as
separate jobs on demand (`workflow_dispatch`), optionally with other suite images.

## Manual runs

### Setup (once)

1. **TLS certificate for this application** — the suite only accepts `https` push endpoints:

   ```sh
   cd receiver/conformance
   mkcert -cert-file certs/localhost.pem -key-file certs/localhost-key.pem localhost 127.0.0.1 ::1 host.docker.internal
   ```

2. **Certificate of the suite** — a locally running suite (`https://localhost.emobix.co.uk:8443`)
   serves a self-signed certificate. Save it so that the receiver trusts it:

   ```sh
   ./fetch-suite-certificate.sh          # writes certs/conformance-suite.pem
   ```

   The certificate is issued for `localhost`, not `localhost.emobix.co.uk`, so
   `cts.transmitter.verify-hostname` is `false` in `application.properties`. The receiver switches
   the JDK HTTP client's host name verification off before it creates the client; should
   something else in the JVM have created a JDK HTTP client earlier, start the application with
   `-Djdk.internal.httpclient.disableHostnameVerification=true` (in dev mode:
   `-Djvm.args=-Djdk.internal.httpclient.disableHostnameVerification=true`). Against a suite with
   a valid certificate remove `cts.transmitter.tls-configuration-name` and set `verify-hostname`
   to `true`.

3. **Test plan in the suite** — create a plan with the configuration of the variant you want to run.
   The configurations are in `easyssf-test-conformance` (`suite-config/*.json` in the jar):

   ```sh
   unzip -p ~/.m2/repository/org/easyssf/easyssf-test-conformance/*/easyssf-test-conformance-*.jar \
     suite-config/receiver-ssf1.0-caepiop1.0-push.json > /tmp/receiver-ssf1.0-caepiop1.0-push.json
   ```

   | Configuration | Plan | Variants | Alias |
   |---|---|---|---|
   | `receiver-ssf1.0-push.json` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=push` | `easyssf-receiver-ssf1_0-push` |
   | `receiver-ssf1.0-poll.json` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=poll` | `easyssf-receiver-ssf1_0-poll` |
   | `receiver-ssf1.0-caepiop1.0-push.json` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=push` | `easyssf-receiver-ssf1_0-caepiop1_0-push` |
   | `receiver-ssf1.0-caepiop1.0-poll.json` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=poll` | `easyssf-receiver-ssf1_0-caepiop1_0-poll` |

   All of them use `ssf_auth_mode=dynamic` and `client_auth_type=client_secret_basic`, the defaults
   of the application. The alias of a configuration (the suite allows no dots in aliases) makes the
   transmitter issuer `https://localhost.emobix.co.uk:8443/test/a/<alias>`, which the application
   takes from `CTS_TRANSMITTER_ISSUER`. `POST /cts/runs/auto` takes any waiting module of the
   suite when none runs under the configured alias, so the default alias in
   `application.properties` does for a single plan at a time. The variants in detail:

   | Variant | Value | `application.properties` |
   |---|---|---|
   | SSF Delivery Mode | `push` or `poll` | `cts.delivery.method` (`CTS_DELIVERY`) |
   | Authentication Variant | `dynamic` (client credentials) or `static` | `cts.auth.mode`, `cts.auth.access-token` |
   | Client Authentication Type | `client_secret_basic` or `client_secret_post` | `cts.auth.client-authentication-method` |
   | SSF Profile | `caep_interop` or `default` | see scenarios |

### Running a test

```sh
mvn -pl receiver/conformance quarkus:dev
# or: CTS_DELIVERY=poll mvn -pl receiver/conformance quarkus:dev
```

IntelliJ IDEA users find the run configurations `conformance (PUSH)` and `conformance (POLL)` in
`.idea/runConfigurations`, next to `Conformance tests (all plans)` for the automated tests.

1. Start the test module in the suite; it waits for the receiver.
2. Start the run the module expects: `curl -sk -X POST https://localhost:9443/cts/runs/auto`. The
   application asks the suite (`/api/runner/running`, `/api/info`) which module waits for the
   receiver, and starts the scenario of that module (see [Scenarios](#scenarios)) against the
   module's test instance with the delivery method of its variant. Modules running under the
   configured alias come first; without one, any waiting module of the suite is taken. The
   response names the module and the run; with no waiting module it is a `404`, with several a
   `409` that lists them (`?issuer=https://localhost.emobix.co.uk:8443/test/a/<alias>` picks one).
   `curl -sk https://localhost:9443/cts/suite/modules | jq` shows what runs in the suite and the
   scenario each module would get.

   To choose the scenario yourself:
   `curl -sk -X POST 'https://localhost:9443/cts/runs?scenario=caep-interop'` — with
   `&issuer=https://localhost.emobix.co.uk:8443/test/a/<alias>&delivery=poll` the run targets another
   test instance or delivery method than configured.
3. Watch it: `curl -sk https://localhost:9443/cts/runs/current | jq` — the run logs every step and
   every SET, and ends by deleting the stream once no SET arrived for `cts.run.idle-timeout`
   (30 seconds, long enough for the pauses the tests make).

Starting a run stops the previous one. `POST /cts/runs/current/stop` ends a run early.

### Running a plan module by module

[`run-plan.sh`](run-plan.sh) does steps 1 to 3 for every module of a plan, waiting for a key press
before each (Enter runs the module, `s` skips it, `q` quits, `a` continues automatically from here
on with a pause of a few seconds between the modules, during which a key press still counts): it
starts the module in the suite, calls `POST /cts/runs/auto` on the receiver, waits for the module
to end and prints its result with the failures and warnings the suite logged, and a summary at the
end. It needs `curl` and `jq`.

```sh
./run-plan.sh                                   # the plan of the module running in the suite
./run-plan.sh sqqbZNqAZRQbv                     # a plan id, or its plan-detail URL
./run-plan.sh -c /tmp/receiver-ssf1.0-caepiop1.0-push.json   # a new plan from a configuration
./run-plan.sh -a 10 <plan>                      # continue automatically after 10 seconds, unless a key is pressed
./run-plan.sh -m verification -y <plan>         # only matching modules, without any pause
```

The suite defaults to `https://localhost.emobix.co.uk:8443` and the receiver to
`https://localhost:9443` (`-s`, `-r`, or `CTS_SUITE_URL`, `CTS_RECEIVER_URL`).

### Scenarios

| Scenario | What the receiver does | Test modules |
|---|---|---|
| `caep-interop` (default) | create stream, check its issuer, read configuration and status, verify (rejecting a wrong state or stream and requesting again), accept events, delete | all modules of the CAEP interop plan; in the default plan everything except the three below |
| `create-delete` | create and delete only | `stream-create-delete` (faster than `caep-interop`, which also passes) |
| `stream-management` | as `caep-interop`, plus update and replace the stream | `happypath` (default plan) |
| `status-update` | as `caep-interop`, plus pause and enable the stream after the verification | `stream-status-update` (default plan) |
| `remove-subject` | as `caep-interop`, plus remove `cts.stream.subject-to-remove` after the verification | `removed-subject-event` (default plan) |

The `stream-issuer-mismatch` module passes with any scenario: the receiver refuses the stream the
suite created with a foreign `iss`, deletes it and ends the run as `REFUSED_STREAM`. Which module
gets which scenario is defined in `ConformanceScenario.forModule` of `easyssf-test-conformance`,
used by `POST /cts/runs/auto` and by the automated tests alike.

## Endpoints

| | |
|---|---|
| `POST /cts/runs/auto?issuer=…` | starts the run the test module waiting in the suite expects; `issuer` names a test instance of the suite, default `cts.transmitter.issuer` |
| `GET /cts/suite/modules?issuer=…` | the test modules running in the suite of that test instance, with the scenario the receiver plays for each |
| `POST /cts/runs?scenario=…&issuer=…&delivery=…` | starts a run; `issuer` and `delivery` default to `cts.transmitter.issuer` and `cts.delivery.method` |
| `GET /cts/runs/current` | the current run: status, stream, log, received SETs |
| `POST /cts/runs/current/stop` | stops the current run and deletes its stream |
| `POST /ssf/push` | the push endpoint the suite delivers to; each run expects its own `Authorization` header, registered with the stream |

## Configuration

`cts.*` binds the `ConformanceReceiverSettings` of `easyssf-test-conformance` (`CtsConfig`); a
value that is not set keeps the library's default. The TLS material is Quarkus configuration:

| Property | |
|---|---|
| `quarkus.tls.key-store.pem.receiver.cert`, `.key` | the certificate the application serves (`CTS_TLS_CERTIFICATE`, `CTS_TLS_KEY`) |
| `quarkus.tls.conformance-suite.trust-store.pem.certs` | the certificate of the suite (`CTS_SUITE_CERTIFICATE`) |
| `cts.transmitter.tls-configuration-name` | the TLS configuration the receiver calls the suite with, `conformance-suite` |
| `cts.transmitter.trust-all-certificates` | trust any certificate of the suite instead (local suite only) |
| `cts.transmitter.verify-hostname` | whether the suite's host name has to match its certificate |
| `cts.transmitter.issuer` | the test instance of the suite (`CTS_TRANSMITTER_ISSUER`) |
| `cts.auth.*` | `mode`, `client-id`, `client-secret`, `scopes`, `client-authentication-method`, `access-token` |
| `cts.delivery.*` | `method`, `push-url`, `poll-interval` |
| `cts.stream.*` | `events-requested`, `subject-to-remove.<member>` |
| `cts.run.*` | `verification-timeout`, `idle-timeout`, `max-duration` |
