# Backend runtime image

Build the Boot JAR with JDK 21, then build the image from the repository root:

```powershell
.\gradlew.bat clean bootJar
docker build --pull --tag loanrun-backend:local .
```

On macOS/Linux use `./gradlew clean bootJar` before the same Docker command.
CI runs `clean build` (including PostgreSQL tests) and builds the image without production secrets.

The runtime is the [official Temurin image](https://hub.docker.com/_/eclipse-temurin),
Java 21.0.13+9 JRE on Ubuntu Jammy, pinned to its multi-platform manifest digest.
The application runs as UID/GID `10001:10001`, with a heap budget of 65% of the container memory limit.
Set an explicit memory limit in the deployment configuration; remaining memory is needed for metaspace, threads and native buffers.
Update the pinned version/digest together after checking upstream releases and rebuilding the image.

The [Docker build context](https://docs.docker.com/build/concepts/context/#dockerignore-files)
allows only the Dockerfile and exact Boot JAR. Source files, private handoff documents, `.env`, DB volumes and local tools
are excluded. Secrets are supplied at runtime and must never be copied or passed as image build arguments.
The JAR path is tied to the current Gradle project/version; update Dockerfile and `.dockerignore` together if it changes.

Quick image checks without connecting to a DB or external API:

```powershell
docker image inspect loanrun-backend:local --format '{{.Config.User}}'
docker run --rm --network none --entrypoint java loanrun-backend:local -version
```

The image alone does not configure PostgreSQL, TLS or a public server. Production must use the `prod` profile,
a private DB network and an HTTPS reverse proxy, with exact `ALLOWED_ORIGINS` and Secure session cookies.
Compose/Nginx and deployment procedures are separate changes under issue #13.
