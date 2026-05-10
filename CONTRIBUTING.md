# Contributing to quarkus-ssf-receiver

Thanks for considering a contribution! This file is short — most of the
project's "how" lives in the [README](README.md).

## Quick reference

```sh
# Build the world (runs spotless:check + license:check + smoke test)
mvn install

# Run just the smoke test
mvn -pl deployment test

# Run an example app in dev mode
mvn -pl examples/example-transmitter-managed-stream quarkus:dev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev
mvn -pl examples/example-receiver-managed-stream    quarkus:dev -Dquarkus.profile=keycloak
```

## Before you push

CI runs on every PR and will fail if any of these aren't clean:

```sh
mvn spotless:apply        # Java formatting + import order (Quarkus eclipse-format.xml)
mvn license:format        # Apache-2.0 headers on every Java source
mvn install               # full reactor build, including tests
```

`spotless:check` and `license:check` are bound to the `verify` phase, so
they're triggered automatically by `mvn install`. Local dev (`mvn quarkus:dev`,
`mvn compile`) is intentionally NOT gated — only release-quality builds are.

If you see `License header missing` or `format violations` in CI:

```sh
mvn spotless:apply && mvn license:format
git add -u && git commit
```

## Adding new code

- **Java source files** must carry the Apache-2.0 header from
  [`etc/license-header.txt`](etc/license-header.txt). `mvn license:format`
  applies it automatically.
- **Imports** follow Quarkus's order (`java`, `javax`, `jakarta`, `org`,
  `com`); see [`etc/quarkus.importorder`](etc/quarkus.importorder). Spotless
  enforces this.
- **REST client interfaces** (anything used via `RestClientBuilder.build(...)`)
  must be added to `SsfReceiverProcessor.indexRestClientInterfaces()` so
  Jandex picks them up at build time.
- **CDI beans** registered by the extension go into
  `SsfReceiverProcessor.registerBeans()` with `setUnremovable()`. ArC will
  otherwise discard them since the extension's sources aren't part of the
  consumer application archive by default.
- **Configuration changes** must update the appropriate javadoc on
  `SsfReceiverConfig` — that's what feeds the Quarkus-generated config
  reference.

## Native image

The native-build CI workflow (`.github/workflows/native.yml`) verifies the
two example apps compile to native image on every PR. If you add classes
that need reflection / resources / proxies for native, register them in
`SsfReceiverProcessor` via the relevant build items
(`ReflectiveClassBuildItem`, `NativeImageResourceBuildItem`, etc.).

To test locally:

```sh
mvn -pl examples/example-receiver-managed-stream -am -Pnative -DskipTests package
./examples/example-receiver-managed-stream/target/quarkus-ssf-receiver-example-receiver-managed-stream-1.0-SNAPSHOT-runner --help
```

## License

By contributing, you agree that your contributions are licensed under
[Apache-2.0](LICENSE).
