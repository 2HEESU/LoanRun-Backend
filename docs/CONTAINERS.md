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

## HTTPS Compose

`compose.production.yml` runs PostgreSQL, the verified backend image and Nginx.
Only Nginx publishes ports; PostgreSQL and backend remain on the private Compose network.
It uses 512 MiB for DB, 768 MiB for backend and 128 MiB for Nginx, plus host/Docker overhead.
Backend runs with a read-only filesystem, dropped capabilities and a temporary `/tmp`.
DB data is stored in the project's `pgdata` volume. Never use `down -v` against a persistent deployment.

Copy `infra/production.env.example` to a protected file outside the checkout and replace the placeholders.
Use a verified commit-tagged image or image digest, exact HTTPS origins and an existing TLS directory containing
`fullchain.pem` and `privkey.pem`. Protect the private key and real env file. No key or certificate is issued by this configuration.

```sh
docker compose --env-file /opt/loanrun/production.env -f compose.production.yml config --quiet
docker compose --env-file /opt/loanrun/production.env -f compose.production.yml up -d --wait
docker compose --env-file /opt/loanrun/production.env -f compose.production.yml ps
```

These are deployment commands, to run only after the target server, costs, TLS and migrations are confirmed.
The env file is read by Compose; runtime configuration is passed explicitly to backend.
`DEMO_MODE=true` enables the existing FREE/PRO demonstration switch; it defaults to false.
FSS and OpenAI remain disabled in this initial deployment configuration. Enabling real integrations requires a separate
reviewed runtime configuration after account/key readiness; no external keys are passed here.

Nginx preserves `/api/` paths and the browser Origin, redirects HTTP to the configured canonical HTTPS origin,
and overwrites forwarded client IP headers. The backend trusts its private reverse proxy via Tomcat native forwarding;
keep backend ports private. Edge limits are 10 API requests/second per IP (burst 20) and 5 report requests/minute (burst 3).
Oversized bodies return 400 JSON; rate limits return 429 JSON; upstream failures return 503 JSON.
The only exposed operational route is `/actuator/health`. Swagger is disabled by the prod profile.
Frontend static hosting remains unconfigured pending agreement with the frontend team; `/` returns 404.
