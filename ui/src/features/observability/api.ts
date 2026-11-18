import { useQuery } from '@tanstack/react-query';
import { http } from '../../api/http';

/**
 * Lecture de Prometheus via la gateway :
 *   GET /v1/metrics/query?query=…  →  Prometheus /api/v1/query   (valeur instantanée)
 *   GET /v1/metrics/alerts         →  Prometheus /api/v1/alerts  (alertes de alerts.yml)
 */
type Vector = { status: 'success'; data: { resultType: 'vector'; result: { metric: Record<string, string>; value: [number, string] }[] } };
type AlertsResponse = { status: 'success'; data: { alerts: PromAlert[] } };

export type PromAlert = {
  labels: Record<string, string> & { alertname: string; job?: string; severity?: string };
  annotations: { summary?: string; description?: string };
  state: 'firing' | 'pending' | 'inactive';
  activeAt: string;
};

/** Résultat d'une requête PromQL regroupé par `job` (NaN, par exemple 0/0 sans trafic, devient null). */
async function byJob(promql: string): Promise<Map<string, number | null>> {
  const res = await http<Vector>(`/v1/metrics/query?query=${encodeURIComponent(promql)}`);
  return new Map(
    res.data.result.map((r) => {
      const n = Number(r.value[1]);
      return [r.metric.job ?? '', Number.isFinite(n) ? n : null];
    }),
  );
}

// Les services exposent http_requests_total / http_request_duration_seconds ; la gateway (Traefik) ses propres
// métriques d'entrypoint, renommées ici en job="gateway" pour tenir sur la même ligne du tableau.
const GW = (expr: string) => `label_replace(${expr}, "job", "gateway", "", "")`;
/**
 * Part des réponses d'une classe de statut ("5xx", "4xx"). Tant qu'un service n'a jamais renvoyé ce statut,
 * la série n'existe pas : le `or … * 0` donne alors 0 % au lieu d'un résultat vide.
 */
const share = (cls: '5xx' | '4xx', traefikCode: string) =>
  `(sum by (job) (rate(http_requests_total{status="${cls}"}[1m])) or sum by (job) (rate(http_requests_total[1m])) * 0)` +
  ` / sum by (job) (rate(http_requests_total[1m]))` +
  ` or ${GW(`(sum(rate(traefik_entrypoint_requests_total{entrypoint="web",code=~"${traefikCode}"}[1m])) or sum(rate(traefik_entrypoint_requests_total{entrypoint="web"}[1m])) * 0) / sum(rate(traefik_entrypoint_requests_total{entrypoint="web"}[1m]))`)}`;

const QUERIES = {
  up: 'up',
  rps: `sum by (job) (rate(http_requests_total[1m])) or ${GW('sum(rate(traefik_entrypoint_requests_total{entrypoint="web"}[1m]))')}`,
  errorRate: share('5xx', '5..'),
  clientErrorRate: share('4xx', '4..'),
  p95:
    `histogram_quantile(0.95, sum by (job, le) (rate(http_request_duration_seconds_bucket[1m])))` +
    ` or ${GW('histogram_quantile(0.95, sum by (le) (rate(traefik_entrypoint_request_duration_seconds_bucket{entrypoint="web"}[1m])))')}`,
  // Relais outbox : pas de trafic HTTP, on suit sa file d'attente (alerte OutboxBacklog au-delà de 100).
  outbox: 'max by (job) (outbox_pending_events)',
  // Kafka (via kafka-exporter, job "kafka") : brokers joignables, messages écrits par seconde (hors topics
  // internes __*), retard total des groupes de consommateurs.
  kafkaBrokers: 'max by (job) (kafka_brokers)',
  kafkaRate: 'sum by (job) (rate(kafka_topic_partition_current_offset{topic!~"__.*"}[1m]))',
  kafkaLag: 'sum by (job) (kafka_consumergroup_lag)',
};

export type Health = {
  up: Map<string, number | null>;
  rps: Map<string, number | null>;
  errorRate: Map<string, number | null>;
  clientErrorRate: Map<string, number | null>;
  p95: Map<string, number | null>;
  outbox: Map<string, number | null>;
  kafkaBrokers: Map<string, number | null>;
  kafkaRate: Map<string, number | null>;
  kafkaLag: Map<string, number | null>;
  alerts: PromAlert[];
  at: Date;
};

/** Santé des services, rafraîchie toutes les 5 s (l'intervalle de collecte de Prometheus). */
export function useHealth() {
  return useQuery({
    queryKey: ['observability', 'health'],
    refetchInterval: 5_000,
    queryFn: async (): Promise<Health> => {
      const [up, rps, errorRate, clientErrorRate, p95, outbox, kafkaBrokers, kafkaRate, kafkaLag, alerts] = await Promise.all([
        byJob(QUERIES.up),
        byJob(QUERIES.rps),
        byJob(QUERIES.errorRate),
        byJob(QUERIES.clientErrorRate),
        byJob(QUERIES.p95),
        byJob(QUERIES.outbox),
        byJob(QUERIES.kafkaBrokers),
        byJob(QUERIES.kafkaRate),
        byJob(QUERIES.kafkaLag),
        http<AlertsResponse>('/v1/metrics/alerts').then((r) => r.data.alerts),
      ]);
      return { up, rps, errorRate, clientErrorRate, p95, outbox, kafkaBrokers, kafkaRate, kafkaLag, alerts, at: new Date() };
    },
  });
}

/**
 * Alertes Prometheus déclenchées, pour le menu (toutes les pages) : une seule requête légère toutes les 15 s.
 * L'onglet Observabilité, lui, interroge tout toutes les 5 s.
 */
export function useFiringAlerts() {
  return useQuery({
    queryKey: ['observability', 'firing'],
    refetchInterval: 15_000,
    queryFn: () => http<AlertsResponse>('/v1/metrics/alerts').then((r) => r.data.alerts.filter((a) => a.state === 'firing')),
  });
}

/** Clé stable d'une alerte : nom + composant. */
export const alertKey = (a: PromAlert) => `${a.labels.alertname}|${a.labels.job ?? ''}`;