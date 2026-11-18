import { useEffect, useRef, useState, type FormEvent, type SyntheticEvent } from 'react';
import { createPortal } from 'react-dom';
import { Button, Notice, fieldClass } from '../../ui/ui';
import { useUpdateStock, type StockItem } from './api';

/**
 * Ajuster le stock d'un produit dans un entrepôt (réapprovisionnement, inventaire, correction).
 * La quantité saisie REMPLACE la quantité actuelle (PUT idempotent du service Stock).
 *
 * Ouverte depuis la fiche produit, elle-même un <dialog> avec son <form> : on la rend dans <body> (portail,
 * pas de <form> imbriqué) et on arrête la propagation de submit/close, que React fait remonter à la fiche.
 */
export function AdjustStockDialog({ item, onClose }: { item: StockItem; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const update = useUpdateStock();
  const [value, setValue] = useState(String(item.quantity));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  const parsed = /^\d+$/.test(value.trim()) ? Number(value.trim()) : null;
  const delta = parsed === null ? null : parsed - item.quantity;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (parsed === null) {
      setError('Nombre entier positif ou nul attendu');
      return;
    }
    if (delta === 0) {
      dialog.current?.close();
      return;
    }
    update.mutate(
      { productId: item.product_id, warehouseId: item.warehouse_id, quantity: parsed },
      { onSuccess: () => dialog.current?.close() },
    );
  }

  const handleClose = (e: SyntheticEvent) => {
    e.stopPropagation();
    onClose();
  };

  return createPortal(
    <dialog
      ref={dialog}
      onClose={handleClose}
      aria-label="Ajuster le stock"
      className="m-auto w-[min(440px,calc(100vw-24px))] rounded-[22px] border border-line bg-surface p-0 text-ink shadow-2xl"
    >
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 p-6">
        <div className="flex flex-col gap-0.5">
          <h2 className="m-0 text-xl font-extrabold">Ajuster le stock</h2>
          <span className="text-sm text-muted">
            {item.warehouse_name} · produit #{item.product_id}
          </span>
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="adjust-qty" className="text-sm font-semibold">
            Nouvelle quantité disponible
          </label>
          <input
            id="adjust-qty"
            autoFocus
            inputMode="numeric"
            className={fieldClass}
            value={value}
            aria-invalid={!!error}
            onChange={(e) => {
              setValue(e.target.value);
              setError(null);
            }}
          />
          {error ? (
            <span className="text-xs font-semibold text-bad">{error}</span>
          ) : (
            <span className="text-xs text-muted">
              Actuellement {item.quantity}
              {delta !== null && delta !== 0 && (
                <span className={`font-bold ${delta > 0 ? 'text-ok' : 'text-bad'}`}> → {delta > 0 ? `+${delta}` : delta}</span>
              )}
            </span>
          )}
        </div>

        <p className="m-0 text-xs text-muted">
          La quantité saisie remplace la quantité actuelle (réapprovisionnement, inventaire, correction). Le service Stock publie
          l'événement <code>inventory.stock-changed</code> dans Kafka.
        </p>

        {update.error && <Notice tone="bad">{update.error.message}</Notice>}

        <div className="flex justify-end gap-2">
          <Button onClick={() => dialog.current?.close()}>Annuler</Button>
          <Button type="submit" variant="primary" disabled={update.isPending}>
            {update.isPending ? 'Enregistrement…' : 'Enregistrer'}
          </Button>
        </div>
      </form>
    </dialog>,
    document.body,
  );
}