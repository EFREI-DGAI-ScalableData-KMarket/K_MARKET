import { useState } from 'react';
import { getRoles } from '../../auth/auth';
import { can, reservedTo } from '../../auth/permissions';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { Locked } from '../../ui/Locked';
import { useWarehouseNames } from '../stock/api';
import { NEXT_STATUS, SHIPMENTS_PAGE_SIZE, useAdvanceShipment, useShipments, type ShipmentStatus } from './api';

const STATUS: Record<ShipmentStatus, { label: string; tone: 'neutral' | 'info' | 'ok' }> = {
  preparing: { label: 'En préparation', tone: 'neutral' },
  shipped: { label: 'Expédiée', tone: 'info' },
  delivered: { label: 'Livrée', tone: 'ok' },
};

const ACTION: Partial<Record<ShipmentStatus, string>> = { preparing: 'Marquer expédiée', shipped: 'Marquer livrée' };

const DATE_TIME = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
const when = (iso: string | null) => (iso ? DATE_TIME.format(new Date(iso)) : '—');

/**
 * Expéditions (GET /shipments, les plus récentes d'abord), filtrables, avec l'avancement du statut.
 * Sans le droit shipments.update (analyst), les boutons restent visibles mais grisés (D9).
 */
export function ShipmentsTable() {
  const canAdvance = can(getRoles(), 'shipments.update');
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [status, setStatus] = useState<ShipmentStatus | null>(null);
  const [page, setPage] = useState(0);

  const shipments = useShipments({ warehouseId, status }, page);
  const names = useWarehouseNames(true);
  const nameOf = new Map(names.data?.map((w) => [w.id, w.name]));
  const advance = useAdvanceShipment();

  const total = shipments.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / SHIPMENTS_PAGE_SIZE));
  const items = shipments.data?.items ?? [];

  return (
    <section className="flex flex-col gap-4 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="m-0 text-[17px] font-bold">Expéditions</h2>
          <span className="text-xs text-muted">{shipments.data ? `${total} expédition${total > 1 ? 's' : ''}` : ' '}</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <label className="flex min-w-[180px] flex-col gap-1 text-xs font-semibold text-muted">
            Entrepôt
            <select
              className={fieldClass}
              value={warehouseId ?? ''}
              onChange={(e) => {
                setWarehouseId(e.target.value ? Number(e.target.value) : null);
                setPage(0);
              }}
            >
              <option value="">Tous</option>
              {names.data?.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex min-w-[160px] flex-col gap-1 text-xs font-semibold text-muted">
            Statut
            <select
              className={fieldClass}
              value={status ?? ''}
              onChange={(e) => {
                setStatus((e.target.value || null) as ShipmentStatus | null);
                setPage(0);
              }}
            >
              <option value="">Tous</option>
              {(Object.keys(STATUS) as ShipmentStatus[]).map((s) => (
                <option key={s} value={s}>
                  {STATUS[s].label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>

      {shipments.error && <Notice tone="bad">{shipments.error.message}</Notice>}
      {advance.error && <Notice tone="bad">{advance.error.message}</Notice>}

      <div className="overflow-x-auto rounded-2xl border border-line">
        <table className="w-full min-w-[760px] border-collapse text-sm">
          <thead>
            <tr className="bg-surface-2 text-left text-xs text-muted">
              <th className="px-4 py-2.5 font-semibold">Expédition</th>
              <th className="px-4 py-2.5 font-semibold">Commande</th>
              <th className="px-4 py-2.5 font-semibold">Entrepôt</th>
              <th className="px-4 py-2.5 font-semibold">Statut</th>
              <th className="px-4 py-2.5 font-semibold">Créée</th>
              <th className="px-4 py-2.5 font-semibold">Expédiée</th>
              <th className="px-4 py-2.5 font-semibold">Livrée</th>
              <th className="px-4 py-2.5 font-semibold">Action</th>
            </tr>
          </thead>
          <tbody>
            {shipments.isPending ? (
              <tr>
                <td colSpan={8} className="px-4 py-4 text-muted">
                  Chargement…
                </td>
              </tr>
            ) : items.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-4 text-muted">
                  Aucune expédition ne correspond.
                </td>
              </tr>
            ) : (
              items.map((s) => {
                const next = NEXT_STATUS[s.status];
                const busy = advance.isPending && advance.variables?.id === s.id;
                return (
                  <tr key={s.id} className="border-t border-line">
                    <td className="px-4 py-2.5 font-semibold">#{s.id}</td>
                    <td className="px-4 py-2.5">#{s.order_id}</td>
                    <td className="px-4 py-2.5">{nameOf.get(s.warehouse_id) ?? `#${s.warehouse_id}`}</td>
                    <td className="px-4 py-2.5">
                      <Badge tone={STATUS[s.status].tone}>{STATUS[s.status].label}</Badge>
                    </td>
                    <td className="px-4 py-2.5 text-muted">{when(s.created_at)}</td>
                    <td className="px-4 py-2.5 text-muted">{when(s.shipped_at)}</td>
                    <td className="px-4 py-2.5 text-muted">{when(s.delivered_at)}</td>
                    <td className="px-4 py-2">
                      {next ? (
                        <Locked locked={!canAdvance} reason={reservedTo('shipments.update')}>
                          <Button
                            className="min-h-9 whitespace-nowrap px-3 text-sm"
                            disabled={!canAdvance || busy}
                            onClick={() => advance.mutate({ id: s.id, status: next })}
                          >
                            {busy ? '…' : ACTION[s.status]}
                          </Button>
                        </Locked>
                      ) : (
                        <span className="text-xs text-muted">Terminée</span>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {pageCount > 1 && (
        <nav aria-label="Pagination des expéditions" className="flex flex-wrap items-center justify-center gap-3">
          <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            ← Précédent
          </Button>
          <span className="min-w-[110px] text-center text-sm font-semibold text-muted">
            Page {page + 1} / {pageCount}
          </span>
          <Button disabled={page + 1 >= pageCount} onClick={() => setPage((p) => p + 1)}>
            Suivant →
          </Button>
        </nav>
      )}
    </section>
  );
}