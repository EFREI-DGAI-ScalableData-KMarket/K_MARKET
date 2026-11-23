import { useState } from 'react';
import { getRoles } from '../../auth/auth';
import { can } from '../../auth/permissions';
import { Badge, Button } from '../../ui/ui';
import { useUpdateOrderStatus, type OrderStatus } from './api';
import { ORDER_STATUS, STEPS, canTransition, stepIndex } from './status';

export function StatusBadge({ status }: { status: OrderStatus }) {
  const s = ORDER_STATUS[status] ?? { label: status, tone: 'neutral' as const };
  return <Badge tone={s.tone}>{status === 'cancelled' ? <s>{s.label}</s> : s.label}</Badge>;
}

/** Frise Payée → Préparation → Expédiée → Livrée, avec l'étape en cours. */
export function StatusSteps({ status }: { status: OrderStatus }) {
  const index = stepIndex(status);
  if (index === null) {
    return (
      <p className="m-0 rounded-2xl bg-surface-2 px-4 py-3 text-sm text-muted">
        {status === 'cancelled' ? 'Commande annulée : elle ne sera pas expédiée.' : 'Paiement refusé : la commande ne sera pas expédiée.'}
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      {index === -1 && <span className="text-sm text-muted">En attente du paiement</span>}
      <ol aria-label="Avancement de la commande" className="m-0 flex list-none gap-1.5 p-0">
        {STEPS.map((step, i) => {
          const done = i <= index;
          const current = i === index;
          return (
            <li key={step.status} aria-current={current ? 'step' : undefined} className="flex flex-1 flex-col gap-1.5">
              <span className={`h-1.5 rounded-full ${done ? 'bg-accent' : 'bg-surface-2'}`} />
              <span className={`text-xs ${current ? 'font-bold text-ink' : done ? 'text-ink' : 'text-muted'}`}>{step.label}</span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** Préparer (admin, logistics) ; annuler en deux clics (admin). `compact` : taille d'une ligne de tableau. */
export function OrderActions({ id, status, compact = false }: { id: number; status: OrderStatus; compact?: boolean }) {
  const roles = getRoles();
  const update = useUpdateOrderStatus();
  const [confirming, setConfirming] = useState(false);
  const prepare = can(roles, 'orders.prepare') && canTransition(status, 'preparing');
  const cancel = can(roles, 'orders.cancel') && canTransition(status, 'cancelled');

  if (!prepare && !cancel) return compact ? <span className="text-xs text-muted">—</span> : null;

  const size = compact ? 'min-h-9 px-3 text-sm' : '';
  const busy = update.isPending;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-2">
        {prepare && (
          <Button
            variant={compact ? 'secondary' : 'primary'}
            className={`whitespace-nowrap ${size}`}
            disabled={busy}
            onClick={(e) => {
              e.stopPropagation(); // la ligne du tableau ouvre le détail au clic
              update.mutate({ id, status: 'preparing' });
            }}
          >
            {busy && update.variables?.status === 'preparing' ? '…' : 'Préparer'}
          </Button>
        )}
        {cancel && (
          <Button
            variant="danger"
            className={`whitespace-nowrap ${size}`}
            disabled={busy}
            onClick={(e) => {
              e.stopPropagation();
              if (!confirming) return setConfirming(true);
              setConfirming(false);
              update.mutate({ id, status: 'cancelled' });
            }}
            onBlur={() => setConfirming(false)}
          >
            {confirming ? "Confirmer l'annulation" : 'Annuler'}
          </Button>
        )}
      </div>
      {update.error && (
        <span role="alert" className="text-xs text-bad">
          {update.error.message}
        </span>
      )}
    </div>
  );
}