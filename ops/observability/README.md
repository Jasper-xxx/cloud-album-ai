# Cloud Album Observability

This stack provisions Prometheus, Alertmanager, and Grafana for the Spring Boot
backend. It includes async task reliability alerts and an automatically loaded
Grafana dashboard.

## Prerequisites

Start `memory-backend` with its management endpoint reachable from Docker:

```powershell
$env:MANAGEMENT_ADDRESS="0.0.0.0"
cd memory-backend
mvn spring-boot:run
```

The default application configuration binds the management endpoint to
`127.0.0.1` for safety. Only use `0.0.0.0` behind a host firewall or private
network. Production deployments should restrict `/actuator/prometheus` at the
network or reverse-proxy layer.

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

Grafana defaults to `admin` / `admin`. Set a real password before starting:

```powershell
$env:GRAFANA_ADMIN_PASSWORD="replace-with-a-strong-password"
docker compose up -d
```

The dashboard is available under `Cloud Album` as
`Cloud Album / Async Task Reliability`.

## Alert thresholds

The default rules alert when:

- the backend cannot be scraped for 2 minutes;
- any dead task remains for 5 minutes;
- pending and retryable failed tasks exceed 100 for 15 minutes;
- at least 10 recent executions have a failure rate above 25%;
- a bounded executor rejects task dispatch;
- a task type has P95 execution time above 5 minutes for 15 minutes;
- a stale running task is recovered;
- backend 5xx responses exceed 5% at meaningful traffic.

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

## Verification

Validate the target and rules:

```bash
docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec prometheus promtool check rules /etc/prometheus/rules/memory-backend-alerts.yml
docker compose exec alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
```

In Prometheus, query:

```promql
up{job="memory-backend"}
memory_async_task_backlog
```
