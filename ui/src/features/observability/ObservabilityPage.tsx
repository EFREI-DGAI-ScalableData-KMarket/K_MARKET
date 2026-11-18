import { Badge, Notice } from '../../ui/ui';
import { useHealth, type Health, type PromAlert } from './api';

/** Composants suivis (jobs de prometheus.yml). */
/** http : API (débit, erreurs, latence) ; outbox : relais sans trafic HTTP (file d'attente) ; kafka : broker (débit, lag). */
type Kind = 'http' | 'outbox' | 'kafka';
const COMPONENTS: { job: string; label: string; tech: string; kind: Kind }[] = [
  { job: 'gateway', label: 'API Gateway', tech: 'Traefik', kind: 'http' },
  { job: 'catalog-service', label: 'Service Catalogue', tech: 'GraphQL · MongoDB', kind: 'http' },
  { job: 'stock-service', label: 'Service Stock', tech: 'REST · PostgreSQL', kind: 'http' },
  // Pas une API : il lit inventory.outbox et publie dans Kafka. Pas de débit HTTP, mais une file d'attente.
  { job: 'stock-outbox-relay', label: 'Relais outbox → Kafka', tech: 'profil « stream »', kind: 'outbox' },
  { job: 'kafka', label: 'Kafka', tech: 'broker · via kafka-exporter (profil « stream »)', kind: 'kafka' },
];

/** Lien vers Grafana. Dans un Codespace, mettre l'URL transférée du port 3000 dans VITE_GRAFANA_URL. */
const GRAFANA_URL = import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3000';

type Status = 'ok' | 'degraded' | 'down' | 'unknown';

/**
 * Même règle que Prometheus (alerts.yml), pour que l'écran et Grafana disent la même chose :
 * arrêté si up = 0 (ServiceDown) ; dégradé si une alerte du job est déclenchée (HighErrorRate, SlowResponses…).
 */
function statusOf(job: string, h: Health): { status: Status; alerts: PromAlert[]; pending: PromAlert[] } {
  const firing = h.alerts.filter((a) => a.state === 'firing' && a.labels.job === job);
  // Seuil dépassé mais pas encore depuis la durée `for:` de l'alerte : le statut reste OK, on le signale.
  const pending = h.alerts.filter((a) => a.state === 'pending' && a.labels.job === job);
  const result = (status: Status) => ({ status, alerts: firing, pending });
  const up = h.up.get(job);
  if (up === undefined) return result('unknown');
  if (up === 0) return result('down');
  // Kafka : l'exporteur peut répondre alors qu'aucun broker n'est joignable.
  if (job === 'kafka' && !(h.kafkaBrokers.get(job) ?? 0)) return result('down');
  return result(firing.length ? 'degraded' : 'ok');
}

const STATUS_BADGE: Record<Status, { tone: 'ok' | 'warn' | 'bad' | 'neutral'; label: string }> = {
  ok: { tone: 'ok', label: 'OK' },
  degraded: { tone: 'warn', label: 'Dégradé' },
  down: { tone: 'bad', label: 'Arrêté' },
  unknown: { tone: 'neutral', label: 'Inconnu' },
};

const secondsSince = (iso: string) => Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));

const num = (v: number | null | undefined, digits: number) =>
  v === null || v === undefined ? '—' : v.toLocaleString('fr-FR', { maximumFractionDigits: digits, minimumFractionDigits: digits });

export function ObservabilityPage() {
  const health = useHealth();
  const h = health.data;
  const rows = h ? COMPONENTS.map((c) => ({ ...c, ...statusOf(c.job, h) })) : [];
  const count = (s: Status) => rows.filter((r) => r.status === s).length;
  const firing = h?.alerts.filter((a) => a.state !== 'inactive') ?? [];

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Observabilité</h1>
          <p className="m-0 text-sm text-muted">
            {h ? `Mis à jour à ${h.at.toLocaleTimeString('fr-FR')} · toutes les 5 s` : 'Métriques Prometheus'}
          </p>
        </div>
        <a
          href={GRAFANA_URL}
          target="_blank"
          rel="noreferrer"
          className="inline-flex min-h-11 items-center gap-2 rounded-full border border-line bg-surface px-4 font-semibold text-ink no-underline transition-colors hover:bg-surface-2"
        >
          Ouvrir Grafana ↗
        </a>
      </div>

      {health.error && <Notice tone="bad">Prometheus injoignable : {health.error.message}</Notice>}

      {h && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Summary label="OK" value={count('ok')} tone="ok" />
          <Summary label="Dégradés" value={count('degraded')} tone="warn" />
          <Summary label="Arrêtés" value={count('down')} tone="bad" />
        </div>
      )}

      <section className="overflow-hidden rounded-[22px] border border-line bg-surface">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] border-collapse text-sm">
            <thead>
              <tr className="bg-surface-2 text-left text-xs text-muted">
                <th className="px-5 py-3 font-semibold">Composant</th>
                <th className="px-5 py-3 font-semibold">Statut</th>
                <th className="px-5 py-3 text-right font-semibold">Débit</th>
                <th className="px-5 py-3 text-right font-semibold">Erreurs 5xx</th>
                <th className="px-5 py-3 text-right font-semibold">Refus 4xx</th>
                <th className="px-5 py-3 text-right font-semibold">Latence p95</th>
              </tr>
            </thead>
            <tbody>
              {!h ? (
                <tr>
                  <td colSpan={6} className="px-5 py-4 text-muted">
                    {health.isPending ? 'Chargement…' : '—'}
                  </td>
                </tr>
              ) : (
                rows.map((r) => {
                  const live = r.status === 'ok' || r.status === 'degraded';
                  const err = h.errorRate.get(r.job);
                  const pct = (v: number | null | undefined) => (v === undefined || v === null ? '—' : `${num(v * 100, 1)} %`);
                  const p95 = h.p95.get(r.job);
                  return (
                    <tr key={r.job} className="border-t border-line align-top">
                      <td className="px-5 py-3.5">
                        <div className="font-semibold">{r.label}</div>
                        <div className="text-xs text-muted">
                          {r.tech} · job <code>{r.job}</code>
                        </div>
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge tone={STATUS_BADGE[r.status].tone}>{STATUS_BADGE[r.status].label}</Badge>
                        {r.alerts.map((a) => (
                          <div key={a.labels.alertname} className="mt-1 text-xs text-warn">
                            {a.annotations.summary ?? a.labels.alertname}
                          </div>
                        ))}
                        {r.pending.map((a) => (
                          <div key={a.labels.alertname} className="mt-1 text-xs text-muted">
                            Seuil dépassé ({a.labels.alertname}) depuis {secondsSince(a.activeAt)} s : en observation avant de passer en dégradé
                          </div>
                        ))}
                      </td>
                      {r.kind === 'http' ? (
                        <>
                          <td className="px-5 py-3.5 text-right font-semibold">{live ? `${num(h.rps.get(r.job), 1)} req/s` : '—'}</td>
                          <td className={`px-5 py-3.5 text-right font-semibold ${err && err > 0.05 ? 'text-bad' : ''}`}>{live ? pct(err) : '—'}</td>
                          <td className="px-5 py-3.5 text-right text-muted">{live ? pct(h.clientErrorRate.get(r.job)) : '—'}</td>
                          <td className={`px-5 py-3.5 text-right font-semibold ${p95 && p95 > 0.5 ? 'text-bad' : ''}`}>
                            {live && p95 !== undefined && p95 !== null ? `${num(p95 * 1000, 0)} ms` : '—'}
                          </td>
                        </>
                      ) : r.kind === 'outbox' ? (
                        <td colSpan={4} className="px-5 py-3.5 text-right text-muted">
                          Pas de trafic HTTP ·{' '}
                          <span className={`font-semibold ${r.alerts.length ? 'text-bad' : (h.outbox.get(r.job) ?? 0) > 100 ? 'text-warn' : 'text-ink'}`}>
                            {live ? `${num(h.outbox.get(r.job), 0)} événement(s) en attente dans l'outbox` : 'file inconnue'}
                          </span>
                        </td>
                      ) : (
                        <td colSpan={4} className="px-5 py-3.5 text-right text-muted">
                          {live ? (
                            <>
                              <span className="font-semibold text-ink">{num(h.kafkaRate.get(r.job) ?? 0, 1)} msg/s</span> écrits ·{' '}
                              {h.kafkaLag.has(r.job) ? (
                                <>
                                  retard des consommateurs <span className="font-semibold text-ink">{num(h.kafkaLag.get(r.job), 0)}</span> message(s)
                                </>
                              ) : (
                                'aucun groupe de consommateurs'
                              )}
                            </>
                          ) : (
                            'broker injoignable'
                          )}
                        </td>
                      )}
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
        <p className="m-0 border-t border-line px-5 py-3 text-xs text-muted">
          Sur la dernière minute. Seuls les 5xx (pannes du service) comptent dans le statut : seuils erreurs &gt; 5 %, p95 &gt; 500 ms
          (alerts.yml). Les 4xx sont des refus attendus (404, 401/403, 409, 422, 429) : à surveiller, mais pas une panne.
        </p>
      </section>

      <section className="flex flex-col gap-3 rounded-[22px] border border-line bg-surface p-5">
        <h2 className="m-0 text-[17px] font-bold">Alertes Prometheus</h2>
        {!h ? null : firing.length === 0 ? (
          <p className="m-0 text-muted">Aucune alerte en cours.</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {firing.map((a) => (
              <li
                key={`${a.labels.alertname}-${a.labels.job ?? ''}`}
                className={`flex flex-wrap items-center gap-3 rounded-2xl px-4 py-3 ${a.labels.severity === 'critical' ? 'bg-bad-bg text-bad' : 'bg-warn-bg text-warn'}`}
              >
                <span className="font-bold">{a.labels.alertname}</span>
                <span className="flex-1 text-sm">{a.annotations.summary}</span>
                <span className="text-xs font-semibold">
                  {a.state === 'firing' ? 'déclenchée' : 'en observation'} depuis {new Date(a.activeAt).toLocaleTimeString('fr-FR')}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  );
}

function Summary({ label, value, tone }: { label: string; value: number; tone: 'ok' | 'warn' | 'bad' }) {
  const active = value > 0;
  const color = { ok: 'bg-ok-bg text-ok', warn: 'bg-warn-bg text-warn', bad: 'bg-bad-bg text-bad' }[tone];
  return (
    <div className={`flex items-baseline justify-between rounded-[22px] border p-5 ${active ? `${color} border-transparent` : 'border-line bg-surface text-muted'}`}>
      <span className="text-sm font-semibold">{label}</span>
      <span className="text-[34px] leading-none font-extrabold">{value}</span>
    </div>
  );
}