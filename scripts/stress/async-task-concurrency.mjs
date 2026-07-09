#!/usr/bin/env node

import { performance } from 'node:perf_hooks'

const options = parseOptions(process.argv.slice(2))

if (options.dryRun) {
  console.log(
    JSON.stringify(
      {
        baseUrl: options.baseUrl,
        path: options.path,
        method: options.method,
        requests: options.requests,
        concurrency: options.concurrency,
        maxErrorRate: options.maxErrorRate,
      },
      null,
      2,
    ),
  )
  process.exit(0)
}

const summary = await runLoad(options)
printSummary(summary)

if (summary.errorRate > options.maxErrorRate) {
  console.error(
    `Error rate ${formatPercent(summary.errorRate)} exceeded threshold ${formatPercent(
      options.maxErrorRate,
    )}`,
  )
  process.exit(1)
}

function parseOptions(args) {
  const parsed = new Map()
  for (const arg of args) {
    if (arg === '--dry-run') {
      parsed.set('dryRun', true)
      continue
    }
    const match = arg.match(/^--([^=]+)=(.*)$/)
    if (match) {
      parsed.set(match[1], match[2])
    }
  }

  return {
    baseUrl: parsed.get('base-url') ?? process.env.STRESS_BASE_URL ?? 'http://127.0.0.1:8088',
    path: parsed.get('path') ?? process.env.STRESS_PATH ?? '/asyncTask/list?current=1&size=20',
    method: (parsed.get('method') ?? process.env.STRESS_METHOD ?? 'GET').toUpperCase(),
    body: parsed.get('body') ?? process.env.STRESS_BODY,
    token: parsed.get('token') ?? process.env.STRESS_TOKEN,
    requests: toPositiveInteger(parsed.get('requests') ?? process.env.STRESS_REQUESTS, 256),
    concurrency: toPositiveInteger(parsed.get('concurrency') ?? process.env.STRESS_CONCURRENCY, 32),
    maxErrorRate: toRate(parsed.get('max-error-rate') ?? process.env.STRESS_MAX_ERROR_RATE, 0.02),
    dryRun: parsed.get('dryRun') === true,
  }
}

async function runLoad(options) {
  const url = new URL(options.path, options.baseUrl)
  const headers = {
    Accept: 'application/json',
    'X-Request-ID': 'stress-async-task',
  }
  if (options.token) {
    headers.Authorization = options.token
  }
  if (options.body) {
    headers['Content-Type'] = 'application/json'
  }

  const latencies = []
  const statuses = new Map()
  let completed = 0
  let cursor = 0

  async function worker() {
    while (cursor < options.requests) {
      cursor += 1
      const startedAt = performance.now()
      try {
        const response = await fetch(url, {
          method: options.method,
          headers,
          body: options.body,
        })
        await response.arrayBuffer()
        statuses.set(response.status, (statuses.get(response.status) ?? 0) + 1)
      } catch {
        statuses.set('network-error', (statuses.get('network-error') ?? 0) + 1)
      } finally {
        latencies.push(performance.now() - startedAt)
        completed += 1
      }
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(options.concurrency, options.requests) }, () => worker()),
  )

  const failures = [...statuses.entries()]
    .filter(([status]) => status === 'network-error' || Number(status) >= 500)
    .reduce((sum, [, count]) => sum + count, 0)

  latencies.sort((a, b) => a - b)

  return {
    completed,
    failures,
    errorRate: completed === 0 ? 1 : failures / completed,
    statuses: Object.fromEntries(statuses),
    latencyMs: {
      p50: percentile(latencies, 50),
      p95: percentile(latencies, 95),
      p99: percentile(latencies, 99),
      max: latencies.at(-1) ?? 0,
    },
  }
}

function printSummary(summary) {
  console.log(
    JSON.stringify(
      {
        ...summary,
        errorRate: formatPercent(summary.errorRate),
      },
      null,
      2,
    ),
  )
}

function percentile(values, percentileValue) {
  if (values.length === 0) {
    return 0
  }
  const index = Math.ceil((percentileValue / 100) * values.length) - 1
  return Math.round(values[Math.max(0, Math.min(index, values.length - 1))])
}

function toPositiveInteger(value, fallback) {
  const parsed = Number.parseInt(value ?? '', 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

function toRate(value, fallback) {
  const parsed = Number.parseFloat(value ?? '')
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    return fallback
  }
  return parsed
}

function formatPercent(value) {
  return `${(value * 100).toFixed(2)}%`
}
