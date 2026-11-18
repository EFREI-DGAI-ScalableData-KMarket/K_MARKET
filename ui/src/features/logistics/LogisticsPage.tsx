import { getRoles } from '../../auth/auth';
import { can } from '../../auth/permissions';
import { Badge, Notice } from '../../ui/ui';
import { FILL_RATE_SOURCE, useWarehouses, type Warehouse } from './api';
import { ShipmentsTable } from './ShipmentsTable';

/** Seuil du sujet : alerte au-dessus de 90 % de remplissage. */
const NEAR_FULL = 0.9;

const pct = (rate: number) => `${(rate * 100).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`;

export function LogisticsPage() {
  const roles = getRoles();
  const canRead = can(roles, 'warehouses.read');

  return (
    <section className="flex flex-col gap-5">
      <div>
        <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Logistique</h1>
        <p className="m-0 text-sm text-muted">Remplissage des entrepôts et suivi des expéditions</p>
      </div>

      {!canRead ? (
        <Notice>Les entrepôts et les expéditions sont réservés aux rôles admin, logistics et analyst.</Notice>
      ) : (
        <>
          <Warehouses />
          <ShipmentsTable />
        </>
      )}
    </section>
  );
}

function Warehouses() {
  const warehouses = useWarehouses();
  const list = warehouses.data ?? [];
  const nearFull = list.filter((w) => w.fill_rate > NEAR_FULL).length;

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="m-0 text-[17px] font-bold">Entrepôts</h2>
        {warehouses.data && (
          <span className={`text-sm font-semibold ${nearFull ? 'text-warn' : 'text-muted'}`}>
            {nearFull ? `${nearFull} en saturation proche (> 90 %)` : `${list.length} entrepôts, aucun au-dessus de 90 %`}
          </span>
        )}
      </div>

      {FILL_RATE_SOURCE.provisional && (
        <Notice tone="neutral">Source du remplissage : {FILL_RATE_SOURCE.label}.</Notice>
      )}
      {warehouses.error && <Notice tone="bad">{warehouses.error.message}</Notice>}

      {warehouses.isPending ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {list.map((w) => (
            <WarehouseCard key={w.id} warehouse={w} />
          ))}
        </div>
      )}
    </section>
  );
}

function WarehouseCard({ warehouse: w }: { warehouse: Warehouse }) {
  const full = w.fill_rate > NEAR_FULL;
  const width = `${Math.min(100, Math.max(0, w.fill_rate * 100))}%`;

  return (
    <article className={`flex flex-col gap-3 rounded-[22px] border p-5 ${full ? 'border-warn bg-warn-bg' : 'border-line bg-surface'}`}>
      <div className="flex items-start justify-between gap-3">
        <h3 className="m-0 text-[15px] font-bold">{w.name}</h3>
        {full ? <Badge tone="warn">Saturation proche</Badge> : <Badge tone="ok">Normal</Badge>}
      </div>
      <span className={`text-[36px] leading-none font-extrabold ${full ? 'text-warn' : ''}`}>{pct(w.fill_rate)}</span>
      <div
        role="progressbar"
        aria-label={`Remplissage de ${w.name}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(w.fill_rate * 100)}
        className="relative h-2.5 overflow-hidden rounded-full bg-surface-2"
      >
        <div className={`h-full rounded-full ${full ? 'bg-warn' : 'bg-accent'}`} style={{ width }} />
        {/* Repère du seuil d'alerte à 90 % */}
        <div aria-hidden="true" className="absolute top-0 h-full w-0.5 bg-ink/40" style={{ left: '90%' }} />
      </div>
      <span className="text-xs text-muted">
        {w.used_units.toLocaleString('fr-FR')} / {w.capacity_units.toLocaleString('fr-FR')} unités
      </span>
    </article>
  );
}