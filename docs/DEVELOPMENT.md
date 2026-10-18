# Development guide

Commands are shown for a Bash-like shell (Linux, macOS, Git Bash on Windows). On Windows PowerShell/cmd,
replace `./mvnw` with `mvnw.cmd`.

## 1. Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| Docker Desktop / Engine + Compose v2 | running everything, and the integration tests | `docker compose version` |
| JDK 21+ | building/running from source | Not needed if you only use Docker. The project compiles with `release 21`; it was also built and tested on JDK 26. |
| Maven | — | Not needed: `./mvnw` downloads Maven 3.9.16 on first use |
| Node 18+ | only for the optional JS unit tests | |

## 2. Everyday workflow

```bash
docker compose up -d postgres redis      # infrastructure only
./mvnw spring-boot:run                   # app on http://localhost:8080 (instance id local-<random>)
```

Edit Java code → stop the app (Ctrl+C) → run again. Edit files under `src/main/resources/static` → for
`spring-boot:run` the resources are served from `target/classes`; re-run the app or run
`./mvnw process-resources` and reload the page.

Two local instances (two terminals):

```bash
SYNCSPHERE_INSTANCE_ID=local-1 ./mvnw spring-boot:run
SERVER_PORT=8082 SYNCSPHERE_INSTANCE_ID=local-2 ./mvnw spring-boot:run
```

PowerShell equivalent of the second command:

```powershell
$env:SERVER_PORT="8082"; $env:SYNCSPHERE_INSTANCE_ID="local-2"; .\mvnw.cmd spring-boot:run
```

## 3. Building

```bash
./mvnw package -DskipTests        # target/syncsphere-0.1.0-SNAPSHOT.jar
java -jar target/syncsphere-0.1.0-SNAPSHOT.jar
```

On Windows a running `java -jar` process keeps the jar file locked; stop it before re-packaging, otherwise
the Spring Boot repackage step fails because it cannot replace the file.

## 4. Tests

```bash
./mvnw test                                   # all 44 Java tests (Docker must be running)
./mvnw test -Dtest=StompDestinationGuardTest  # one class
./mvnw test -Dtest='CollaborationWebSocketTest#staleEditIsRejectedPrivatelyWithTheLatestSnapshot'
node --test src/test/js/merge.test.mjs        # 7 JS tests for the merge helpers
```

| Test class | Kind | What it proves |
|---|---|---|
| `StompDestinationGuardTest` | unit | allow-list accepts the 3 SEND / 5 SUBSCRIBE patterns and drops everything else |
| `SyncEventJsonTest` | unit | JSON wire format (type discriminator, ISO dates), round trip, topic routing |
| `RecentEventIdsTest` | unit | duplicate detection and bounded eviction |
| `DataStoreOutageResponseTest` | web slice (`@WebMvcTest`, mocked services) | DB/Redis outages map to HTTP 503 |
| `DocumentServiceIntegrationTest` | integration (PostgreSQL) | revisions, stale-edit rejection, 6-thread race has one winner, persistence |
| `DocumentRestApiTest` | integration (HTTP) | status codes, Location header, validation errors, readiness |
| `CollaborationWebSocketTest` | integration (WebSocket + Redis + DB) | join snapshot, broadcast, conflict, NOT_JOINED, validation, forged SEND blocked, presence join/leave, presence self-healing, cursors |
| `RedisSyncTest` | integration (Redis) | an event published by "another instance" reaches clients exactly once; malformed messages ignored |
| `MultiInstanceSyncTest` | integration (2 Spring contexts) | edit on instance 1 reaches a client on instance 2; cross-instance conflict |

How the integration tests get their infrastructure: `IntegrationTestBase` starts `postgres:17-alpine` and
`redis:7-alpine` with Testcontainers once per JVM and passes their random ports to Spring with
`@DynamicPropertySource`. They do not use (or disturb) the Compose stack.

## 5. Database migrations (Flyway)

- Migrations live in `src/main/resources/db/migration` and are named `V<version>__<description>.sql`.
- On startup Flyway applies any migration not yet listed in the `flyway_schema_history` table. With several
  instances starting at once, Flyway takes a PostgreSQL lock so only one applies it (seen in the Compose logs:
  app-1 "Successfully applied 1 migration", app-2 "Schema is up to date").
- Hibernate runs with `ddl-auto: validate`: it never changes the schema, it only fails startup if the entity
  and the table disagree.
- **Never edit a migration that has already run** (Flyway stores a checksum and refuses to start). Add a new
  file instead, e.g. `V2__add_document_owner.sql`.

```bash
docker compose exec postgres psql -U syncsphere -d syncsphere -c "select * from flyway_schema_history;"
```

## 6. Docker commands

| Command | What it does |
|---|---|
| `docker compose up --build` | build the app image, start all services in the foreground (Ctrl+C stops them) |
| `docker compose up -d` | start in the background |
| `docker compose up -d postgres redis` | start only the infrastructure (for `./mvnw spring-boot:run`) |
| `docker compose ps` | list containers with status and health |
| `docker compose logs -f app-1 app-2` | follow application logs |
| `docker compose restart app-1` | restart one instance (its browsers reconnect automatically) |
| `docker compose stop redis` / `start redis` | simulate a Redis outage |
| `docker compose exec postgres psql -U syncsphere -d syncsphere` | SQL shell |
| `docker compose exec redis redis-cli` | Redis shell |
| `docker compose down` | stop and remove containers and network; **keeps** the `postgres-data` volume |
| `docker compose down -v` | also delete the volume (all documents are lost) |
| `docker compose build --no-cache` | rebuild the image from scratch |

## 7. Debugging

### Logs

Application log lines use `key=value` fields, e.g.

```
Edit accepted documentId=9e7f8a1a-... participantId=c9fb596b-... revision=8 chars=194
Edit rejected (stale) documentId=... baseRevision=1 currentRevision=2
Dropped STOMP SEND to disallowed destination=/topic/documents/... sessionId=...
Redis publish failed, delivering to local clients only eventId=... documentId=... cause=...
```

More detail for one area: add `--logging.level.org.springframework.web.socket=DEBUG` (or
`-Dlogging.level...` for tests). For machine-readable logs, Spring Boot 3.4+ can emit JSON:
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (not enabled by default here).

### WebSocket problems

1. Browser DevTools → Network → filter "WS" → click `/ws` → "Messages" shows every STOMP frame.
2. Handshake status 403 → origin not allowed. 101 → connected.
3. Nothing arrives after SEND → check the server log for `Dropped STOMP` (wrong destination) or a
   `VALIDATION_FAILED` message on `/user/queue/errors`.
4. Check health: `curl http://localhost:8080/actuator/health/readiness`.

### Redis

```bash
docker compose exec redis redis-cli PUBSUB NUMSUB syncsphere:sync-events   # expect 2 (one per instance)
docker compose exec redis redis-cli SUBSCRIBE syncsphere:sync-events       # watch events live (Ctrl+C)
docker compose exec redis redis-cli --scan --pattern "syncsphere:*"
docker compose exec redis redis-cli HGETALL syncsphere:presence:<documentId>
docker compose exec redis redis-cli TTL syncsphere:instance:app-1
```

### PostgreSQL

```bash
docker compose exec postgres psql -U syncsphere -d syncsphere \
  -c "select id, title, revision, length(content) as chars, updated_at from documents order by updated_at desc;"
```

## 8. Manual verification checklist

These checks were performed on the Compose stack while writing the project (October 2026):

| Check | Result |
|---|---|
| `docker compose up --build` | postgres, redis, app-1, app-2 all `healthy`; containers run as user `syncsphere` (uid 100) |
| Two browsers, `:8080` and `:8081`, same document | edits, presence and cursor positions crossed instances |
| Non-overlapping concurrent edits | one rejected with REVISION_CONFLICT, merged by the client, resent as the next revision; both tabs converged |
| Overlapping concurrent edits | loser's text shown in the rejected-draft panel; editor shows the winning revision |
| `docker compose restart app-1` | Alice's tab reconnected and resynchronized at the stored revision |
| `docker compose stop redis`, edit on app-1 | edit saved and acknowledged locally; app-2 user caught up via snapshot after Redis restarted; presence restored by heartbeat; live sync resumed |
| `docker compose stop postgres` | REST `503`, readiness DOWN (503), liveness UP; edit typed during the outage was retried and committed after restart |
| `docker compose down` then `up -d` | document still present with the same revision (named volume) |
| WebSocket handshake with `Origin: http://evil.example` | `403` |
