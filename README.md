# Introduction to Microservices — Tasks 1 to 3

Two Spring Boot services that store MP3 files and their song metadata, each with its own PostgreSQL database.
Task 2 containerizes them: each service has a Dockerfile, and Docker Compose builds and starts the whole system.
Task 3 adds service discovery: a Eureka Server registry, both services as Eureka clients, and two Song Service
instances in Docker Compose that the Resource Service balances its calls across.

| Service          | Port                               | Database                       | Responsibility                                   |
|------------------|------------------------------------|--------------------------------|--------------------------------------------------|
| eureka-server    | 8761                               | —                              | Service registry (Eureka), web UI on `/`         |
| resource-service | 8081                               | `resource-db` (host port 5432) | Stores MP3 files, extracts tags with Apache Tika |
| song-service     | 8082 (in Docker: 8082 and 8083, two instances) | `song-db` (host port 5433) | Stores song metadata, keyed by the Resource ID |

```
                     eureka-server (registry)
                    ▲        ▲            ▲
          register, │        │ register   │ register
          fetch     │        │            │
client ──► resource-service ──REST, load-balanced──► song-service (instance 1, instance 2)
                │                                        │
           resource-db                                song-db (shared by both instances)
```

- `POST /resources` validates the MP3, stores it, then calls `POST /songs` with the new resource id and the
  extracted tags (duration converted to `mm:ss`). If the Song Service call fails, the stored resource is
  rolled back.
- `DELETE /resources` deletes the existing resources and then calls `DELETE /songs` for exactly those ids.
  If the Song Service call fails, the local delete is rolled back.
- The Song Service does not call the Resource Service.

## Service discovery and load balancing

- **Eureka Server** (`eureka-server/`, port 8761) is a standalone registry: it does not register itself and
  does not fetch a registry from peers (`eureka.client.register-with-eureka=false`,
  `eureka.client.fetch-registry=false`). Its web UI at `http://localhost:8761` lists the registered instances.
- **Registration:** the Resource Service and the Song Service are Eureka clients and register under their
  `spring.application.name` (`RESOURCE-SERVICE`, `SONG-SERVICE`). With `eureka.instance.prefer-ip-address=true`
  each instance registers its own IP address, so the two Song containers appear as two distinct instances.
- **Discovery and load balancing happen in the Resource Service (client side).** It calls
  `http://song-service`: a Eureka service id, not a host. A `@LoadBalanced` `RestClient.Builder`
  (`LoadBalancedRestClientConfig`, Spring Cloud LoadBalancer) resolves it to one of the registered instances
  for each request, round robin by default. Eureka itself only stores the registry.
- The 2 s connect / 5 s read timeouts of the Song Service client are unchanged.
- Heartbeats and registry fetches run every 5 s (the course's tip for local development; Eureka's default is
  30 s): `eureka.instance.lease-renewal-interval-in-seconds=5`, `eureka.client.registry-fetch-interval-seconds=5`.
- Versions: Spring Boot 3.5.16 with Spring Cloud 2025.0.3, the release train for Boot 3.5.

## Project structure

```
├── eureka-server/               # Maven project + Dockerfile (Eureka Server)
├── init-scripts/
│   ├── resource-db/init.sql     # creates the resource table
│   └── song-db/init.sql         # creates the song table
├── resource-service/            # Maven project + Dockerfile
├── song-service/                # Maven project + Dockerfile
├── compose.yaml                 # Eureka, both databases and both services
├── .env                         # configuration for compose.yaml (demo values only)
└── .gitignore
```

## Prerequisites

- Docker with Docker Compose v2. This is enough to run everything in Docker.
- For local execution and the tests: **JDK 21 is required**. Build and run with Maven 3.9+ (no Maven wrapper is
  included) or with IntelliJ IDEA configured with JDK 21 and its Maven support.

## Configuration

`application.properties` reads every environment-specific value from an environment variable and falls back
to a default for local execution. In Docker, Compose sets these variables from `.env`.

| Property                     | Environment variable          | Local default                                  | Value in Docker (from `.env`)                     |
|------------------------------|-------------------------------|------------------------------------------------|---------------------------------------------------|
| `spring.datasource.url`      | `SPRING_DATASOURCE_URL`       | `jdbc:postgresql://localhost:5432/resource-db` (resource), `…localhost:5433/song-db` (song) | `RESOURCE_DB_URL` / `SONG_DB_URL`: `jdbc:postgresql://resource-db:5432/resource-db`, `…song-db:5432/song-db` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME`  | `postgres`                                     | `RESOURCE_DB_USER` / `SONG_DB_USER`               |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD`  | `postgres`                                     | `RESOURCE_DB_PASSWORD` / `SONG_DB_PASSWORD`       |
| `eureka.client.service-url.defaultZone` (both services) | `EUREKA_URL` | `http://localhost:8761/eureka` | `http://eureka-server:8761/eureka`        |

- `song-service.url` (Resource Service) is always `http://song-service`, the Eureka service id, in both run
  modes. There is no host or port to configure for the Song Service.
- Inside Docker, the containers reach each other by Compose service name on Compose's default network, so both
  databases use container port 5432.
- The database containers get their settings the same way: `.env` defines `RESOURCE_DB_NAME`, `RESOURCE_DB_USER`,
  `RESOURCE_DB_PASSWORD` (and the `SONG_DB_*` equivalents), and `compose.yaml` passes them to the postgres image
  as `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD`.
- `.env` is committed on purpose and contains **demo values only** (`postgres`/`postgres`). Don't put real
  credentials in it.

No profiles are involved. The same configuration works in both run modes.

## Run everything in Docker

Run all Compose commands from the repository root. Compose then acts only on this project (its name is the
directory name); add `-p <name>` to every command if you use another project name.

```bash
docker compose up -d --build
```

This command:
- builds the three images (eureka-server, resource-service, song-service) from source;
- creates fresh databases on the first start (if you ran Task 1 from this directory before, see
  [Leftover Task 1 data](#leftover-task-1-data) first);
- starts Eureka, then the services once Eureka and their databases are healthy;
- runs **two Song Service instances** (`deploy.replicas: 2` in `compose.yaml`). Each takes one host port from
  the range 8082–8083.

`docker compose up -d --build --scale song-service=2` is equivalent; the port range gives each instance its own
port either way.

Ports: Eureka `http://localhost:8761`, Resource Service `http://localhost:8081`, Song Service
`http://localhost:8082` and `http://localhost:8083`, databases 5432 and 5433. All of them are bound to
`127.0.0.1`, so they are reachable from this machine only.

Check the result:

```bash
docker ps                                             # 1 eureka, 2 databases, 1 resource, 2 song containers
docker compose logs -f resource-service song-service  # startup logs of the services
```

Then open `http://localhost:8761`: **RESOURCE-SERVICE** must have one instance and **SONG-SERVICE** two.
Before the first upload, see [Startup order and readiness](#startup-order-and-readiness).

```bash
docker compose ps                   # status and health
docker compose stop                 # stop the containers, keep them and their data
```

Apply code or configuration changes with `docker compose up -d --build`. It rebuilds the images and recreates
only the containers that changed.

### Clean start (screening)

> **Data lifecycle.** The databases keep their data in anonymous volumes of the postgres image; `compose.yaml`
> declares no data volume. `docker compose down` removes this project's containers and network, so the next
> `up` starts with **fresh, empty databases**, while the old anonymous volumes stay on disk, unused.
> `docker compose down -v` also removes the anonymous volumes **of the containers it removes**, deleting the
> current databases' data. Volumes already left behind by an earlier plain `down` are not removed by it.

```bash
docker compose down                 # removes this project's containers and network; old anonymous volumes stay on disk
docker compose up -d --build        # fresh databases
```

Then check `docker ps`, the logs and the Eureka UI as above, follow
[Startup order and readiness](#startup-order-and-readiness), and run the Postman collection. Finish with
`docker compose down` (same data-lifecycle note).

### Images

Each Dockerfile uses a two-stage build:
- **Build stage:** `maven:3.9-eclipse-temurin-21-alpine` copies `pom.xml` first and downloads dependencies
  (`mvn dependency:go-offline`) in their own cached layer, then copies `src` and packages the jar without tests.
- **Runtime stage:** `eclipse-temurin:21-jre-alpine` contains only the jar and runs it with `CMD ["java", "-jar", "app.jar"]`.

Build or run a single image without Compose. The Eureka Server needs nothing else:

```bash
docker build -t eureka-server eureka-server
docker run --rm -p 127.0.0.1:8761:8761 eureka-server
```

## Run the services locally (databases in Docker)

Only the databases run in Docker; Eureka and the services use the defaults from `application.properties`.

```bash
docker compose up -d resource-db song-db
docker ps                                   # both database containers are running
```

Then start, in this order, either in IntelliJ IDEA (run `EurekaServerApplication`, `SongServiceApplication` and
`ResourceServiceApplication`) or with Maven from the repository root, each in its own terminal:

```bash
mvn -f eureka-server/pom.xml spring-boot:run      # terminal 1, keeps running
mvn -f song-service/pom.xml spring-boot:run       # terminal 2, keeps running
mvn -f resource-service/pom.xml spring-boot:run   # terminal 3, keeps running
```

Open `http://localhost:8761`: both services must be listed (one Song Service instance in this mode). Locally
each service registers the machine's network address.

Don't run the services locally and in Docker at the same time, because both use ports 8761 and 8081–8083.

## Database initialization

- The postgres image creates the database from `POSTGRES_DB` and then runs the mounted
  `init-scripts/<db>/init.sql`, which creates the tables only.
- Hibernate does not create or change the schema (`spring.jpa.hibernate.ddl-auto=none`). No Flyway, Liquibase
  or Spring SQL initialization is used.
- The init scripts run **only when the data directory is empty**, i.e. on a container's first start. If you
  change an entity, update the matching `init.sql` by hand and reset the databases; nothing checks that they match.
- Both Song Service instances share `song-db`.

## Reset the databases

`compose.yaml` declares no data volume, but the postgres image keeps its data in an anonymous volume. Compose
reattaches that volume when it restarts or recreates a container (`restart`, `stop`/`start`, `up -d` after a
change, `up -d --force-recreate`), so the data stays and `init.sql` does not run again. Only removing the
containers gives empty databases. For a clean state, for example before running the Postman collection:

> **Warning:** `down -v` deletes the current databases' data (the anonymous volumes of the containers it removes).
> Volumes left behind by an earlier plain `down` are no longer attached and stay on disk.

```bash
docker compose down -v          # deletes the current databases' data
docker compose up -d --build
```

Plain `down` also gives empty databases, but it leaves the old anonymous volumes behind unused, and a later
`down -v` does not remove them, because they are no longer attached to any container.

### Leftover Task 1 data

Task 1 used the same Compose project (the directory name) and database services. If its database containers
still exist, Task 2 reattaches their volumes, so `init.sql` is skipped and the Task 1 rows stay; ids then no
longer start at 1. Reset once as shown above. This deletes all Task 1 data.

## Startup order and readiness

- Eureka starts first; the services start once Eureka is **healthy** (its `/actuator/health` answers) and their
  database is **healthy** (`pg_isready -h localhost`, which postgres accepts only after the init scripts have
  finished).
- `resource-service` starts after `song-service` but does not wait for it to be ready; it calls the Song
  Service only when handling a request.
- **A healthy Eureka and a registered instance do not yet mean that the Resource Service can find the Song
  Service.** Eureka serves a new registration to clients with a delay, and the Resource Service sees it only on
  its next registry fetch. Observed in local runs: up to about 25 s after the Song Service registered.
- Before the first upload, wait until `http://localhost:8761` shows all instances (1 Resource, 2 Song in
  Docker), then about 15 s more (three registry fetches). **This wait is a heuristic only**: it does not
  guarantee that the Resource Service, or its load balancer, already knows the Song Service instances.
- **The first upload is the end-to-end check:** on fresh databases it must return `{"id": 1}`, and
  `GET /songs/1` must return its metadata.
- If that first upload fails (500), it is rolled back, but it has consumed an id from the identity sequence, so
  the following ids no longer match the Postman collection. Reset the disposable test databases
  ([Reset the databases](#reset-the-databases)) and start again. Don't send repeated uploads to probe readiness.
- `docker compose restart` ignores the health conditions, and there is no restart policy: a service that cannot
  reach its database at startup exits and stays stopped. Use `docker compose up -d`, which applies the conditions.

## API

Resource Service (`http://localhost:8081`):

| Method   | Path                  | Description                                          |
|----------|-----------------------|------------------------------------------------------|
| `POST`   | `/resources`          | Upload an MP3 (`Content-Type: audio/mpeg`), returns `{"id": 1}` |
| `GET`    | `/resources/{id}`     | Download the MP3 bytes (`audio/mpeg`)                |
| `DELETE` | `/resources?id=1,2`   | Delete resources and their metadata, returns `{"ids": [1]}` |

Song Service (`http://localhost:8082`; in Docker the second instance also on `http://localhost:8083`):

| Method   | Path              | Description                                   |
|----------|-------------------|-----------------------------------------------|
| `POST`   | `/songs`          | Create metadata, returns `{"id": 1}`; 409 if the id exists |
| `GET`    | `/songs/{id}`     | Get metadata                                  |
| `DELETE` | `/songs?id=1,2`   | Delete metadata, returns `{"ids": [1]}`       |

Errors use `{"errorMessage": "...", "errorCode": "400"}`; validation errors add a `details` object with one
message per field.

## Tests

The tests run on the host and use the databases on `localhost:5432` and `localhost:5433`. Those ports are
published in both run modes. Each test suite works in its own schema (`resource_test`, `song_test`), which the
tests create and drop with Hibernate (`create-drop`, test profile only). The application tables are not
touched, and the application itself never generates DDL. The test profiles disable the Eureka client, so no
Eureka Server is needed; the load-balancing tests use local stub instances.

From the repository root:

```bash
docker compose up -d resource-db song-db
mvn -f eureka-server/pom.xml test
mvn -f song-service/pom.xml test
mvn -f resource-service/pom.xml test
```

Four tests use the course sample files and are skipped unless the directory is given:

```bash
mvn -f resource-service/pom.xml test -Dsample.mp3.dir=/path/to/sample-mp3-file/mp3
```

The Docker images are built without running the tests (`-Dmaven.test.skip=true`).

## Postman collection

Use the course's `introduction_to_microservices.postman_collection.json` with the course sample MP3, in either
run mode. In both upload requests ("Upload Valid Mp3 Resource (200)" and "! Upload Mp3 Before the Next Request"),
select the sample MP3 as the binary body and save the request; otherwise the Collection Runner sends no file and
the upload fails with 400. Then:

1. Set the collection variables `resource_service_url` = `http://localhost:8081` and
   `song_service_url` = `http://localhost:8082` (in Docker, `http://localhost:8083` reaches the other Song
   instance; both share the same database).
2. Start from empty databases, so the responses match the literal values in the API response specification
   (for example `{"id": 1}`). Leftover resources such as 101 or 102 would also change the delete result.
   In Docker, this **deletes the current databases' data**:
   `docker compose down -v && docker compose up -d --build`.
3. Wait as described in [Startup order and readiness](#startup-order-and-readiness). The collection's first
   upload must return `{"id": 1}`; if it fails, reset the databases and start again.
4. Run the requests in collection order.

## Known limitations

- No distributed transaction. If the Song Service gives no response (for example a read timeout) or the
  local commit fails after it succeeded, an upload can leave orphaned metadata, and a delete can leave
  resources without metadata (retrying the delete fixes this).
- A database connection (and, on delete, row locks) is held during the Song Service call. Timeouts
  (`song-service.connect-timeout=2s`, `song-service.read-timeout=5s`) apply per connect and per read,
  not to the whole call. There are no retries.
- Discovery lags registration (see above). If the Resource Service knows no Song Service instance, the request
  fails with 500 and is rolled back. An instance that stops abruptly (without deregistering) may stay
  discoverable for some time, because of the registry's eviction timing, Eureka's self-preservation mode and
  the clients' cached registries; calls routed to it fail meanwhile.
- Load balancing is client side in the Resource Service; there is no API Gateway in front of the Song Service
  instances.
- Spring Cloud 2025.0.x, the only release train for Spring Boot 3.5, is marked end-of-life upstream.
- Concurrent deletes of the same id: the second response can list an id it did not delete itself.
- MP3 validity is a frame-chain plausibility check (`Mp3FrameScanner`), not a full decoder. Tika 3.3.3
  misreads large ID3v2.4 frames, which can lose tags that come after them.
- Uploads are read into memory; no request size limit is configured.
- The containers run as root, and the base images are referenced by tag, not by digest.
