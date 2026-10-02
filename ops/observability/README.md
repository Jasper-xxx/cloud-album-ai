# Cloud Album Observability

This optional local stack provisions Prometheus, Alertmanager, and Grafana for
the Spring Boot backend. It includes async task, upload, quota, MinIO, HTTP,
and circuit-breaker alerts, plus an automatically loaded async-task dashboard.
It does not monitor the Dify workflow or AI model quality.

Reviewed against the repository configuration on 2026-10-02. This documentation
update did not start the stack or verify live metrics. Further monitoring work
remains outside the personal-edition delivery scope.

## Prerequisites

The application listens on `BACKEND_PORT` (default `8088`); management is a
separate listener at `MANAGEMENT_PORT` (default `8089`). Prometheus scrapes
`http://host.docker.internal:8089/actuator/prometheus` every 15 seconds.
When the user chooses to start the backend for this stack, make management
reachable from Docker. Run the following from the repository root:

```powershell
$env:MANAGEMENT_ADDRESS="0.0.0.0"
cd backend
mvn spring-boot:run
```

The default application configuration exposes only `health` and `prometheus`
and binds the management endpoint to
`127.0.0.1` for safety. Only use `0.0.0.0` behind a host firewall or private
network. Restrict management access at the network or reverse-proxy layer.
If `MANAGEMENT_PORT` changes, update the target in `prometheus/prometheus.yml`
as well. Setting `MANAGEMENT_ADDRESS` does not change the backend API port.

## Start

From this directory:

```bash
docker compose up -d
```

Open:

- Prometheus: <http://localhost:9090>
- Prometheus alerts: <http://localhost:9090/alerts>
- Alertmanager: <http://localhost:9093>
- Grafana: <http://localhost:3000>

The Compose file publishes `9090`, `9093`, and `3000` on all host interfaces;
it does not bind them to loopback. For access limited to this machine, change
the port mappings to `127.0.0.1:<host-port>:<container-port>` or restrict them
with a host firewall before starting.

Grafana defaults to `admin` / `admin`. Set a local password before the first start:

```powershell
$env:GRAFANA_ADMIN_PASSWORD="replace-with-a-strong-password"
docker compose up -d
```

The dashboard is available under the `Cloud Album` folder as
`Cloud Album / Async Task Reliability`. Provisioning reloads the dashboard
file every 30 seconds and disables saving UI edits; maintain the JSON under
`grafana/dashboards/`. Data is persisted in the three Compose named volumes.
Changing the admin-password environment variable does not reset an account
already stored in Grafana's existing volume.

## Alert thresholds

The default rules alert when:

- the backend cannot be scraped for 2 minutes;
- any dead task remains for 5 minutes;
- pending and retryable failed tasks exceed 100 for 15 minutes;
- at least 10 executions in a 10-minute window have a failure rate above 25%,
  sustained for 10 minutes;
- a bounded executor rejects dispatch in a 5-minute window, sustained for 1 minute;
- a task type has P95 execution time above 5 minutes for 15 minutes;
- a stale running task is recovered;
- backend 5xx responses exceed 5% at at least 0.1 requests/second, sustained for 10 minutes;
- observed MinIO operations have an error rate above 5% at at least 0.1 operations/second,
  sustained for 10 minutes;
- more than 5 upload failures occur in a 10-minute window, sustained for 5 minutes;
- at least one quota reservation is rejected in a 10-minute window, sustained for 2 minutes;
- an external-service circuit breaker stays open for 2 minutes.

Tune thresholds in
`prometheus/rules/memory-backend-alerts.yml` using production traffic,
executor capacity, and task latency objectives.

## Notification routing

The bundled Alertmanager receiver intentionally has no external destination, so
local development does not send accidental notifications. Alerts are still
grouped and visible in Prometheus and Alertmanager.

For production, add a webhook, email, or supported chat receiver under
`receivers` in `alertmanager/alertmanager.yml`. Store credentials outside Git
and mount the generated configuration or secret at deployment time.

## Optional verification

These commands are for a maintainer to run when verification is wanted; they
were not executed during this documentation update. With the stack running,
validate the target and rules:

```bash
docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec prometheus promtool check rules /etc/prometheus/rules/memory-backend-alerts.yml
docker compose exec alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
```

In Prometheus, query:

```promql
up{job="memory-backend"}
memory_async_task_backlog
memory_upload_lifecycle_events_total
memory_storage_quota_reservations_total
memory_minio_operation_duration_seconds_count
resilience4j_circuitbreaker_state
```

Upload, quota, and MinIO meters are registered when the corresponding operation
is recorded. An absent series before any activity is not itself evidence of a
scrape failure; first inspect `up{job="memory-backend"}` and the target page.
Quota and upload metric labels are normalized to uppercase, as used by the rules.
