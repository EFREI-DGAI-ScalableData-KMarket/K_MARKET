import { useState, type ReactNode } from 'react';
import { getRoles } from '../../auth/auth';
import { can, reservedTo } from '../../auth/permissions';
import { Badge, Button, Notice } from '../../ui/ui';
import { Locked } from '../../ui/Locked';
import { AdjustStockDialog } from '../stock/AdjustStockDialog';
import { useProductStock, type StockItem } from '../stock/api';
import { stockLevel } from '../stock/levels';

/** Section titrée d'une fiche produit (consultation ou modification). */
export function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-col gap-0.5">
        <h3 className="m-0 text-sm font-bold uppercase tracking-wide text-muted">{title}</h3>
        {hint && <p className="m-0 text-sm text-muted">{hint}</p>}
      </div>
      {children}
    </section>
  );
}

const DATE_TIME = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' });

/**
 * Stock du produit entrepôt par entrepôt (le total des cartes est la somme de ces lignes).
 * admin et logistics peuvent ajuster chaque ligne (PUT /stock/{produit}/warehouses/{entrepôt}) ;
 * pour les autres rôles, le bouton reste visible mais grisé (D9).
 */
export function StockByWarehouse({ productId }: { productId: number }) {
  const stock = useProductStock(productId);
  const canAdjust = can(getRoles(), 'stock.update');
  const [adjusting, setAdjusting] = useState<StockItem | null>(null);

  if (stock.isPending) return <p className="m-0 text-sm text-muted">Chargement du stock…</p>;
  if (stock.error) return <Notice tone="warn">Stock indisponible : {stock.error.message}</Notice>;
  if (stock.data === null) return <p className="m-0 text-sm text-muted">Aucune ligne de stock pour ce produit.</p>;

  const { total_quantity, by_warehouse } = stock.data;
  const level = stockLevel(total_quantity);
  const lastUpdate = by_warehouse.map((w) => w.updated_at).sort().at(-1);

  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto rounded-2xl border border-line">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="bg-surface-2 text-left text-xs text-muted">
              <th className="px-4 py-2 font-semibold">Entrepôt</th>
              <th className="px-4 py-2 text-right font-semibold">Quantité</th>
              <th className="w-px px-4 py-2" aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {by_warehouse.map((w) => (
              <tr key={w.warehouse_id} className="border-t border-line">
                <td className="px-4 py-2.5">{w.warehouse_name}</td>
                <td className="px-4 py-2.5 text-right font-semibold">{w.quantity}</td>
                <td className="px-3 py-1.5 text-right">
                  <Locked locked={!canAdjust} reason={reservedTo('stock.update')}>
                    <Button className="min-h-9 px-3 text-sm" disabled={!canAdjust} onClick={() => setAdjusting(w)}>
                      Ajuster
                    </Button>
                  </Locked>
                </td>
              </tr>
            ))}
            <tr className="border-t border-line bg-surface-2">
              <td className="px-4 py-2.5 font-bold">Total</td>
              <td className="px-4 py-2.5 text-right">
                <span className="inline-flex items-center gap-2 font-extrabold">
                  {level === 'out' && <Badge tone="bad">Rupture</Badge>}
                  {level === 'low' && <Badge tone="warn">Stock bas</Badge>}
                  {total_quantity}
                </span>
              </td>
              <td />
            </tr>
          </tbody>
        </table>
      </div>
      {lastUpdate && <span className="text-xs text-muted">Dernière mise à jour : {DATE_TIME.format(new Date(lastUpdate))}</span>}
      {adjusting && <AdjustStockDialog item={{ ...adjusting, product_id: productId }} onClose={() => setAdjusting(null)} />}
    </div>
  );
}