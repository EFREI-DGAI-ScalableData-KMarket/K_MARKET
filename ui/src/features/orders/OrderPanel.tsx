import { useEffect } from 'react';
import { ApiError } from '../../api/http';
import { formatCents, formatDateTime } from '../../lib/format';
import { Button, Notice } from '../../ui/ui';
import { useProductsByIds } from '../catalog/api';
import { useOrder } from './api';
import { OrderActions, StatusBadge, StatusSteps } from './parts';

/** Panneau latéral d'une commande : avancement, actions, client, articles (noms et vignettes du catalogue). Échap ou clic à côté pour fermer. */
export function OrderPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const order = useOrder(id);
  const o = order.data;
  const products = useProductsByIds(o?.items.map((i) => i.product_id) ?? []);
  const byId = new Map(products.data?.map((p) => [p.product_id, p]));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const notFound = order.error instanceof ApiError && order.error.status === 404;

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <button type="button" aria-label="Fermer le détail" tabIndex={-1} onClick={onClose} className="absolute inset-0 cursor-default bg-ink/30" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={`Commande ${id}`}
        className="relative flex h-full w-full max-w-[480px] flex-col gap-5 overflow-y-auto border-l border-line bg-surface p-6 shadow-xl"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col items-start gap-1.5">
            <h2 className="m-0 text-[22px] font-extrabold">Commande #{id}</h2>
            {o && <StatusBadge status={o.status} />}
          </div>
          <Button autoFocus onClick={onClose} aria-label="Fermer" className="min-h-9 px-3">
            ✕
          </Button>
        </div>

        {order.isPending && <p className="m-0 text-muted">Chargement…</p>}
        {order.error && <Notice tone="bad">{notFound ? `La commande #${id} n'existe pas.` : order.error.message}</Notice>}

        {o && (
          <>
            <StatusSteps status={o.status} />
            <OrderActions id={o.id} status={o.status} />

            <section className="flex flex-col gap-0.5 rounded-2xl bg-surface-2 p-4">
              <h3 className="m-0 mb-1 text-xs font-semibold text-muted">Client</h3>
              {o.customer ? (
                <>
                  <span className="font-bold">{o.customer.name}</span>
                  <span className="text-sm text-muted">
                    {o.customer.email} · n° {o.customer.id}
                  </span>
                </>
              ) : (
                <span>n° {o.customer_id}</span>
              )}
            </section>

            <section className="flex flex-col gap-3">
              <h3 className="m-0 text-[15px] font-bold">Articles ({o.items.length})</h3>
              <ul className="m-0 flex list-none flex-col gap-3 p-0">
                {o.items.map((item, i) => {
                  const p = byId.get(item.product_id);
                  return (
                    <li key={`${item.product_id}-${i}`} className="flex items-center gap-3">
                      {p?.image_url ? (
                        <img src={p.image_url} alt="" className="size-12 shrink-0 rounded-xl object-cover" />
                      ) : (
                        <span aria-hidden="true" className="size-12 shrink-0 rounded-xl bg-surface-2" />
                      )}
                      <div className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate font-semibold">{p?.name ?? item.product_name}</span>
                        <span className="text-xs text-muted">
                          {item.quantity} × {formatCents(item.unit_price_cents)}
                        </span>
                      </div>
                      <span className="font-semibold tabular-nums">{formatCents(item.line_total_cents)}</span>
                    </li>
                  );
                })}
              </ul>
              <div className="flex justify-between border-t border-line pt-3 font-extrabold">
                <span>Total</span>
                <span className="tabular-nums">{formatCents(o.total_cents)}</span>
              </div>
            </section>

            <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-muted">Créée</dt>
              <dd className="m-0">{formatDateTime(o.created_at)}</dd>
              <dt className="text-muted">Mise à jour</dt>
              <dd className="m-0">{formatDateTime(o.updated_at)}</dd>
            </dl>
          </>
        )}
      </aside>
    </div>
  );
}