import { useCallback, useState, type FormEvent } from 'react';
import { getRoles } from '../../auth/auth';
import { can } from '../../auth/permissions';
import { formatCents, formatDateTime, formatRelative } from '../../lib/format';
import { Button, Notice, fieldClass } from '../../ui/ui';
import { ORDERS_PAGE_SIZE, ORDER_STATUSES, useOrderCount, useOrders, type OrderStatus } from './api';
import { OrderPanel } from './OrderPanel';
import { OrderActions, StatusBadge } from './parts';
import { ORDER_STATUS, TONE_DOT } from './status';

/** Compteurs du haut : ce que l'équipe doit traiter en premier. Un clic filtre la liste. */
const KPIS: { status: OrderStatus; label: string; hint: string }[] = [
  { status: 'paid', label: 'À préparer', hint: 'payées, en attente de préparation' },
  { status: 'preparing', label: 'En préparation', hint: 'en cours en entrepôt' },
  { status: 'shipped', label: 'Expédiées', hint: 'en route vers le client' },
  { status: 'payment_failed', label: 'Paiements refusés', hint: 'à surveiller' },
];

export function OrdersPage() {
  const roles = getRoles();
  const showActions = can(roles, 'orders.prepare') || can(roles, 'orders.cancel');

  const [status, setStatus] = useState<OrderStatus | null>(null);
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const close = useCallback(() => setSelected(null), []);

  const orders = useOrders({ status, customerId }, page);
  const total = orders.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / ORDERS_PAGE_SIZE));
  const items = orders.data?.items ?? [];
  const cols = showActions ? 7 : 6;

  function filterBy(next: OrderStatus | null) {
    setStatus(next);
    setPage(0);
  }

  function openSearch(e: FormEvent) {
    e.preventDefault();
    const id = Number(search.replace(/\D/g, ''));
    if (id > 0) setSelected(id);
  }

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Commandes</h1>
          <p className="m-0 text-sm text-muted">Suivi des commandes clients, du paiement à la livraison</p>
        </div>
        <form onSubmit={openSearch} className="flex gap-2" role="search">
          <input
            className={`${fieldClass} w-[190px]`}
            inputMode="numeric"
            placeholder="N° de commande"
            aria-label="Ouvrir une commande par son numéro"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <Button type="submit">Ouvrir</Button>
        </form>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {KPIS.map((k) => (
          <KpiCard key={k.status} {...k} active={status === k.status} onClick={() => filterBy(status === k.status ? null : k.status)} />
        ))}
      </div>

      <section className="flex flex-col gap-4 rounded-[22px] border border-line bg-surface p-5">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex gap-1 overflow-x-auto" aria-label="Filtrer par statut">
            <StatusTab value={null} active={status === null} onClick={() => filterBy(null)} />
            {ORDER_STATUSES.map((s) => (
              <StatusTab key={s} value={s} active={status === s} onClick={() => filterBy(s)} />
            ))}
          </div>
          <label className="flex w-[140px] flex-col gap-1 text-xs font-semibold text-muted">
            N° client
            <input
              className={fieldClass}
              inputMode="numeric"
              placeholder="Tous"
              value={customerId ?? ''}
              onChange={(e) => {
                const digits = e.target.value.replace(/\D/g, '');
                setCustomerId(digits ? Number(digits) : null);
                setPage(0);
              }}
            />
          </label>
        </div>

        {orders.error && <Notice tone="bad">{orders.error.message}</Notice>}

        <div className="overflow-x-auto rounded-2xl border border-line">
          <table className="w-full min-w-[800px] border-collapse text-sm">
            <thead>
              <tr className="bg-surface-2 text-left text-xs text-muted">
                <th className="px-4 py-2.5 font-semibold">Commande</th>
                <th className="px-4 py-2.5 font-semibold">Client</th>
                <th className="px-4 py-2.5 font-semibold">Statut</th>
                <th className="px-4 py-2.5 text-right font-semibold">Total</th>
                <th className="px-4 py-2.5 font-semibold">Créée</th>
                <th className="px-4 py-2.5 font-semibold">Mise à jour</th>
                {showActions && <th className="px-4 py-2.5 font-semibold">Action</th>}
              </tr>
            </thead>
            <tbody>
              {orders.isPending ? (
                <tr>
                  <td colSpan={cols} className="px-4 py-4 text-muted">
                    Chargement…
                  </td>
                </tr>
              ) : items.length === 0 ? (
                <tr>
                  <td colSpan={cols} className="px-4 py-4 text-muted">
                    Aucune commande ne correspond.
                  </td>
                </tr>
              ) : (
                items.map((o) => (
                  <tr key={o.id} onClick={() => setSelected(o.id)} className="cursor-pointer border-t border-line transition-colors hover:bg-surface-2">
                    <td className="px-4 py-2.5">
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          setSelected(o.id);
                        }}
                        className="cursor-pointer font-semibold text-ink underline-offset-2 hover:underline"
                      >
                        #{o.id}
                      </button>
                    </td>
                    <td className="px-4 py-2.5">
                      {o.customer ? <span className="font-semibold">{o.customer.name}</span> : <span className="text-muted">n° {o.customer_id}</span>}
                    </td>
                    <td className="px-4 py-2.5">
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="px-4 py-2.5 text-right font-semibold tabular-nums">{formatCents(o.total_cents)}</td>
                    <td className="px-4 py-2.5 text-muted" title={formatDateTime(o.created_at)}>
                      {formatRelative(o.created_at)}
                    </td>
                    <td className="px-4 py-2.5 text-muted" title={formatDateTime(o.updated_at)}>
                      {formatRelative(o.updated_at)}
                    </td>
                    {showActions && (
                      <td className="px-4 py-2">
                        <OrderActions id={o.id} status={o.status} compact />
                      </td>
                    )}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {pageCount > 1 && (
          <nav aria-label="Pagination des commandes" className="flex flex-wrap items-center justify-center gap-3">
            <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              ← Précédent
            </Button>
            <span className="min-w-[130px] text-center text-sm font-semibold text-muted">
              Page {page + 1} / {pageCount.toLocaleString('fr-FR')}
            </span>
            <Button disabled={page + 1 >= pageCount} onClick={() => setPage((p) => p + 1)}>
              Suivant →
            </Button>
          </nav>
        )}
      </section>

      {selected !== null && <OrderPanel id={selected} onClose={close} />}
    </section>
  );
}

function KpiCard({ status, label, hint, active, onClick }: { status: OrderStatus; label: string; hint: string; active: boolean; onClick: () => void }) {
  const count = useOrderCount(status);
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`flex cursor-pointer flex-col gap-1.5 rounded-[22px] border p-4 text-left transition-colors ${
        active ? 'border-accent bg-surface-2' : 'border-line bg-surface hover:bg-surface-2'
      }`}
    >
      <span className="flex items-center gap-2 text-sm font-semibold text-muted">
        <span aria-hidden="true" className={`size-2.5 rounded-full ${TONE_DOT[ORDER_STATUS[status].tone]}`} />
        {label}
      </span>
      <span className="text-[32px] leading-none font-extrabold tabular-nums">{count.data?.toLocaleString('fr-FR') ?? '…'}</span>
      <span className="text-xs text-muted">{hint}</span>
    </button>
  );
}

function StatusTab({ value, active, onClick }: { value: OrderStatus | null; active: boolean; onClick: () => void }) {
  const count = useOrderCount(value);
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`flex min-h-9 shrink-0 cursor-pointer items-center gap-1.5 rounded-full px-3.5 text-sm font-semibold whitespace-nowrap transition-colors ${
        active ? 'bg-accent text-accent-ink' : 'text-muted hover:bg-surface-2 hover:text-ink'
      }`}
    >
      {value ? ORDER_STATUS[value].label : 'Toutes'}
      {count.data !== undefined && <span className={`text-xs ${active ? '' : 'opacity-70'}`}>{count.data.toLocaleString('fr-FR')}</span>}
    </button>
  );
}