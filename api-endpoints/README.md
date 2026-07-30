# idekanin: api-endpoints

The API gateway of the resolver: the single entry point and the single writer
of worker output to the result cache. It accepts lookups over REST (`202` +
`Location`), publishes them to RabbitMQ, consumes the workers' status events
(`claimed` / `done` / `failed`), applies them to the ArangoDB cache, times out
stale lookups with a scheduled sweep, and serves polls of the results URL.
See the [root README](../README.md) for the full lifecycle.




## Development
### Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

### Running the end-to-end test suite

The module includes a Testcontainers-based end-to-end test that starts real
RabbitMQ and ArangoDB containers and drives the pipeline from HTTP POST to
cached GET. It lives in `src/test/java/e2e/EndToEndLookupIT.java`, is bound to
the `failsafe` phase and is skipped by default. A working Docker daemon is
required.

```shell
./mvnw verify -DskipITs=false
```

On Colima, export the Docker socket before running the tests:

```shell
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### Cache TTL

The gateway ensures a TTL index on the shared ArangoDB cache collection at
start-up. The retention window is set by `cache.ttl-seconds` in
`application.properties` (default `3600`). Set it to `0` to disable expiry;
no TTL index is then created.

### Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

### Creating a native executable

You can create a native executable using:
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/api-endpoints-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

