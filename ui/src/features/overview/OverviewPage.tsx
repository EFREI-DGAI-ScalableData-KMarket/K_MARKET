import { useEffect, useState } from 'react';
import { formatCents, formatRelative } from '../../lib/format';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { useActiveProductCount, useProductsByIds } from '../catalog/api';
import { CATEGORY_LABELS } from '../catalog/categories';
import { useLowStock, useWarehouseNames } from '../stock/api';
import { LOW_STOCK_THRESHOLD, stockLevel } from '../stock/levels';
import { STALE_AFTER_MS, useDashboardSummary, type DashboardSummary } from './api';

const HOUR = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', timeZone: 'Europe/Paris' });
const plural = (n: number, word: string) => `${n.toLocaleString('fr-FR')} ${word}${n > 1 ? 's' : ''}`;
const pct = (rate: number) => `${(rate * 100).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`;

export function OverviewPage() {
  const today = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());
  const summary = useDashboardSummary();

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Aujourd'hui, {today}</h1>
        <Freshness dataAsOf={summary.data?.data_as_of} />
      </div>
      {summary.error && <Notice tone="bad">{summary.error.message}</Notice>}
      <Kpis today={summary.data?.today} />
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[3fr_2fr]">
        <HourlyChart hourly={summary.data?.hourly} />
        <LiveAlerts summary={summary.data} />
      </div>
      <LowStock />
    </section>
  );
}

/** Heure courante, mise à jour régulièrement : « il y a 3 min » doit vieillir même si les données ne changent pas. */
function useNow(intervalMs: number) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}

/** Âge des données du flux temps réel ; avertissement si le job Spark n'écrit plus. */
function Freshness({ dataAsOf }: { dataAsOf: string | null | undefined }) {
  const now = useNow(5000);
  if (dataAsOf === undefined) return null;
  if (dataAsOf === null) return <Notice tone="neutral">Le flux temps réel n'a encore produit aucun chiffre aujourd'hui.</Notice>;
  if (now - new Date(dataAsOf).getTime() > STALE_AFTER_MS) {
    return (
      <Notice tone="warn">
        Flux temps réel interrompu : dernière mise à jour {formatRelative(dataAsOf, now)}. Les chiffres et les alertes ne sont plus à jour.
      </Notice>
    );
  }
  return <span className="text-xs text-muted">Flux temps réel (Kafka → Spark) · mis à jour {formatRelative(dataAsOf, now)}</span>;
}

/** Les 4 chiffres du jour du sujet : 3 viennent du flux temps réel, le taux de rupture des services Catalogue et Stock. */
function Kpis({ today }: { today?: DashboardSummary['today'] }) {
  return (
    <section aria-label="Chiffres du jour" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Kpi
        label="Commandes du jour"
        value={today ? today.orders.toLocaleString('fr-FR') : '…'}
        hint={today ? `${plural(today.orders_paid, 'payée')} · ${plural(today.payments_failed, 'paiement refusé')}` : 'Chargement'}
      />
      <Kpi label="Chiffre d'affaires" value={today ? formatCents(today.revenue_cents) : '…'} hint="Commandes payées depuis minuit" />
      <Kpi
        label="Panier moyen"
        value={!today ? '…' : today.average_basket_cents === null ? '—' : formatCents(today.average_basket_cents)}
        hint="Chiffre d'affaires ÷ commandes payées"
      />
      <StockoutKpi />
    </section>
  );
}

/** Commandes heure par heure depuis minuit (heure de Paris) ; les heures sans commande sont affichées à zéro. */
function HourlyChart({ hourly }: { hourly?: DashboardSummary['hourly'] }) {
  const rows = fillHours(hourly ?? []);
  const max = Math.max(1, ...rows.map((h) => h.orders));

  return (
    <section className="flex flex-col gap-4 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex flex-col">
        <h2 className="m-0 text-[17px] font-bold">Commandes par heure</h2>
        <span className="text-xs text-muted">Depuis minuit · calculé par Spark à partir des événements Kafka</span>
      </div>
      {!hourly ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : rows.length === 0 ? (
        <p className="m-0 text-muted">Pas encore de commande aujourd'hui.</p>
      ) : (
        <div
          role="img"
          aria-label={`Commandes par heure : ${rows.map((h) => `${HOUR.format(new Date(h.hour))} ${h.orders}`).join(', ')}`}
          className="flex h-52 items-end gap-1.5"
        >
          {rows.map((h) => (
            <div
              key={h.hour}
              title={`${HOUR.format(new Date(h.hour))} : ${plural(h.orders, 'commande')} · ${formatCents(h.revenue_cents)}`}
              className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1"
            >
              <span className="text-[11px] font-semibold tabular-nums">{h.orders || ''}</span>
              <div className="w-full max-w-10 rounded-t-lg bg-accent" style={{ height: `${(h.orders / max) * 80}%` }} />
              <span className="text-[11px] text-muted">{HOUR.format(new Date(h.hour))}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/** Complète les heures manquantes entre la première et la dernière heure reçues (une heure sans commande = 0). */
function fillHours(hourly: DashboardSummary['hourly']) {
  if (hourly.length === 0) return [];
  const byHour = new Map(hourly.map((h) => [new Date(h.hour).getTime(), h]));
  const first = new Date(hourly[0].hour).getTime();
  const last = new Date(hourly[hourly.length - 1].hour).getTime();
  const rows: DashboardSummary['hourly'] = [];
  for (let t = first; t <= last; t += 3_600_000) {
    rows.push(byHour.get(t) ?? { hour: new Date(t).toISOString(), orders: 0, revenue_cents: 0 });
  }
  return rows;
}

/** Alertes levées par le job temps réel : ruptures proches (la plus urgente d'abord) et entrepôts au-dessus de 90 %. */
function LiveAlerts({ summary }: { summary?: DashboardSummary }) {
  const alerts = summary?.stock_alerts ?? [];
  const saturated = (summary?.warehouses ?? []).filter((w) => w.near_full);
  const products = useProductsByIds(alerts.map((a) => a.product_id));
  const byId = new Map(products.data?.map((p) => [p.product_id, p]));
  const names = useWarehouseNames(saturated.length > 0);
  const nameOf = new Map(names.data?.map((w) => [w.id, w.name]));
  const count = alerts.length + saturated.length;

  return (
    <section aria-live="polite" className="flex flex-col gap-3 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="m-0 text-[17px] font-bold">Alertes en direct</h2>
        <span className={`text-sm font-semibold ${count ? 'text-bad' : 'text-muted'}`}>{count ? `${count} en cours` : 'Aucune'}</span>
      </div>
      {!summary ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : count === 0 ? (
        <p className="m-0 text-sm text-muted">Aucune rupture proche ni entrepôt au-dessus de 90 %.</p>
      ) : (
        <ul className="m-0 flex max-h-[300px] list-none flex-col gap-2 overflow-y-auto p-0">
          {alerts.map((a) => {
            const p = byId.get(a.product_id);
            return (
              <li key={`p${a.product_id}`} className="flex items-center gap-3 rounded-2xl bg-bad-bg px-4 py-3">
                {p?.image_url && <img src={p.image_url} alt="" className="size-10 shrink-0 rounded-lg object-cover" />}
                <div className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate font-semibold">{p?.name ?? `Produit #${a.product_id}`}</span>
                  <span className="text-xs text-muted">
                    Rupture proche · {plural(a.remaining_units, 'unité')} · {a.units_per_minute.toLocaleString('fr-FR')} vendues/min · depuis{' '}
                    {formatRelative(a.raised_at).replace('il y a ', '')}
                  </span>
                </div>
                <Badge tone="bad">{a.remaining_units === 0 ? 'Rupture' : `≈ ${Math.max(1, Math.round(a.minutes_left))} min`}</Badge>
              </li>
            );
          })}
          {saturated.map((w) => (
            <li key={`w${w.warehouse_id}`} className="flex items-center gap-3 rounded-2xl bg-warn-bg px-4 py-3">
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate font-semibold">{nameOf.get(w.warehouse_id) ?? `Entrepôt #${w.warehouse_id}`}</span>
                <span className="text-xs text-muted">
                  Saturation proche · {w.used_units.toLocaleString('fr-FR')} / {w.capacity_units.toLocaleString('fr-FR')} unités
                  {w.near_full_since && ` · depuis ${formatRelative(w.near_full_since).replace('il y a ', '')}`}
                </span>
              </div>
              <Badge tone="warn">{pct(w.fill_rate)}</Badge>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * Taux de rupture = produits actifs dont le stock total (tous entrepôts) vaut 0 ÷ produits actifs.
 * Les archivés sont exclus des deux côtés : ils ne sont plus en vente. Ex. 1 / 288 = 0,35 % → « 0,3 % ».
 * Un produit actif sans aucune ligne de stock n'est pas compté : /stock/low ne connaît que les lignes existantes.
 */
function StockoutKpi() {
  const active = useActiveProductCount();
  const zero = useLowStock(0, 200);
  const zeroIds = zero.data?.map((s) => s.product_id) ?? [];
  const products = useProductsByIds(zeroIds);
  const out = products.data?.filter((p) => p.status === 'active').length ?? 0;

  const error = active.error ?? zero.error ?? products.error;
  const loading = active.isPending || zero.isPending || (zeroIds.length > 0 && products.isPending);
  if (error) return <Kpi label="Taux de rupture" value="—" hint={error.message} />;
  if (loading || active.data === undefined) return <Kpi label="Taux de rupture" value="…" hint="Calcul en cours" />;

  const rate = active.data ? (out / active.data) * 100 : 0;
  return (
    <Kpi
      label="Taux de rupture"
      value={`${rate.toFixed(1).replace('.', ',')} %`}
      hint={`${out} produit${out > 1 ? 's' : ''} sur ${active.data} en vente ${out > 1 ? "n'ont" : "n'a"} plus de stock`}
      warn={out > 0}
    />
  );
}

function Kpi({ label, value, hint, warn }: { label: string; value: string; hint: string; warn?: boolean }) {
  return (
    <div className={`flex min-h-[150px] flex-col gap-2 rounded-[22px] border p-5 ${warn ? 'border-warn-bg bg-warn-bg text-warn' : 'border-line bg-surface'}`}>
      <span className={`text-sm font-semibold ${warn ? '' : 'text-muted'}`}>{label}</span>
      <span className="mt-auto text-[40px] leading-none font-extrabold tracking-tight">{value}</span>
      <span className={`text-xs ${warn ? 'font-semibold' : 'text-muted'}`}>{hint}</span>
    </div>
  );
}

function LowStock() {
  // Fourchette saisie (en texte, pour laisser l'utilisateur effacer un champ pendant la frappe).
  const [minInput, setMinInput] = useState('0');
  const [maxInput, setMaxInput] = useState(String(LOW_STOCK_THRESHOLD));
  const [range, setRange] = useState({ min: 0, max: LOW_STOCK_THRESHOLD });
  const rangeError = parseRange(minInput, maxInput).error;

  // On n'interroge qu'une fois la saisie valide et stable (300 ms).
  useEffect(() => {
    const parsed = parseRange(minInput, maxInput);
    if (parsed.error) return;
    const t = setTimeout(() => setRange({ min: parsed.min, max: parsed.max }), 300);
    return () => clearTimeout(t);
  }, [minInput, maxInput]);

  // Le service Stock filtre « ≤ max », trié du plus bas au plus haut ; le minimum est appliqué ici.
  // Il ne connaît pas le statut catalogue : les premières lignes peuvent être des produits archivés ou
  // absents du catalogue. On en demande donc 200 (le maximum de productsByIds) avant de les écarter.
  const low = useLowStock(range.max, 200);
  const inRange = (low.data ?? []).filter((s) => s.total_quantity >= range.min);
  const ids = inRange.map((s) => s.product_id);
  const products = useProductsByIds(ids);
  const byId = new Map(products.data?.map((p) => [p.product_id, p]));
  const rows = inRange
    .map((s) => ({ stock: s, product: byId.get(s.product_id) }))
    .filter((r) => r.product?.status === 'active');

  const out = rows.filter((r) => r.stock.total_quantity === 0).length;
  const truncated = (low.data?.length ?? 0) >= 200;
  const error = low.error ?? products.error;
  const loading = low.isPending || (ids.length > 0 && products.isPending);
  const isDefault = range.min === 0 && range.max === LOW_STOCK_THRESHOLD;

  return (
    <section className="flex flex-col gap-3 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="m-0 text-[17px] font-bold">{isDefault ? 'Ruptures et stocks bas' : `Stock entre ${range.min} et ${range.max}`}</h2>
          <span className="text-xs text-muted">
            {rows.length} produit{rows.length > 1 ? 's' : ''} actif{rows.length > 1 ? 's' : ''}
            {out > 0 && ` · dont ${out} en rupture`}
          </span>
        </div>
        <fieldset className="m-0 flex flex-wrap items-end gap-2 border-0 p-0">
          <legend className="sr-only">Fourchette de stock</legend>
          <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
            Stock min.
            <input className={`${fieldClass} w-24`} inputMode="numeric" value={minInput} aria-invalid={!!rangeError} onChange={(e) => setMinInput(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
            Stock max.
            <input className={`${fieldClass} w-24`} inputMode="numeric" value={maxInput} aria-invalid={!!rangeError} onChange={(e) => setMaxInput(e.target.value)} />
          </label>
          {!isDefault && (
            <Button
              onClick={() => {
                setMinInput('0');
                setMaxInput(String(LOW_STOCK_THRESHOLD));
              }}
            >
              Réinitialiser
            </Button>
          )}
        </fieldset>
      </div>
      {rangeError && <span className="text-xs font-semibold text-bad">{rangeError}</span>}
      {truncated && (
        <Notice tone="warn">Plus de 200 lignes de stock sous {range.max} : seules les 200 plus basses sont examinées.</Notice>
      )}
      {error ? (
        <Notice tone="bad">{error.message}</Notice>
      ) : loading ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : rows.length === 0 ? (
        <p className="m-0 text-muted">Aucun produit actif dans cette fourchette.</p>
      ) : (
        <ul className="m-0 flex max-h-[440px] list-none flex-col gap-2 overflow-y-auto p-0">
          {rows.map(({ stock, product }) => (
            <li key={stock.product_id} className="flex flex-wrap items-center gap-3 rounded-2xl bg-surface-2 px-4 py-3">
              <div className="flex min-w-[200px] flex-1 flex-col">
                <span className="font-semibold">{product!.name}</span>
                <span className="text-xs text-muted">
                  {CATEGORY_LABELS[product!.category]} · #{stock.product_id}
                </span>
              </div>
              <span className="text-sm font-bold">{stock.total_quantity === 0 ? 'plus aucune unité' : `${stock.total_quantity} en stock`}</span>
              <LevelBadge quantity={stock.total_quantity} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function LevelBadge({ quantity }: { quantity: number }) {
  const level = stockLevel(quantity);
  if (level === 'out') return <Badge tone="bad">Rupture</Badge>;
  if (level === 'low') return <Badge tone="warn">Stock bas</Badge>;
  return null;
}

function parseRange(minInput: string, maxInput: string): { min: number; max: number; error?: string } {
  const isInt = (v: string) => /^\d+$/.test(v.trim());
  if (!isInt(minInput) || !isInt(maxInput)) return { min: 0, max: 0, error: 'Nombres entiers attendus.' };
  const min = Number(minInput);
  const max = Number(maxInput);
  if (min > max) return { min, max, error: 'Le minimum doit être inférieur ou égal au maximum.' };
  return { min, max };
}