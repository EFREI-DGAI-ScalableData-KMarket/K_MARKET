import { Link } from 'react-router';
import { formatCents } from '../../lib/format';
import { Button, Notice, fieldClass } from '../../ui/ui';
import { useProductsByIds } from '../catalog/api';
import { MAX_QUANTITY, cart, useCart } from './cart';

/** Panier : prix et noms relus au catalogue (un produit archivé depuis devient « indisponible »). */
export function CartPage() {
  const lines = useCart();
  const products = useProductsByIds(lines.map((l) => l.product_id));
  const byId = new Map(products.data?.map((p) => [p.product_id, p]));
  const loaded = lines.length === 0 || products.isSuccess;

  const rows = lines.map((line) => {
    const p = byId.get(line.product_id);
    return { line, product: p && p.status === 'active' ? p : undefined };
  });
  const total = rows.reduce((sum, r) => sum + (r.product ? r.product.price_cents * r.line.quantity : 0), 0);
  const articles = rows.reduce((n, r) => n + (r.product ? r.line.quantity : 0), 0);

  if (lines.length === 0) {
    return (
      <section className="flex flex-col items-start gap-3">
        <h1 className="m-0 text-[28px] font-extrabold">Votre panier est vide</h1>
        <Link to="/">← Découvrir la boutique</Link>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-5">
      <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Panier</h1>
      {products.error && <Notice tone="bad">{products.error.message}</Notice>}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_340px]">
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {rows.map(({ line, product: p }) => (
            <li key={line.product_id} className="flex items-center gap-4 rounded-[22px] border border-line bg-surface p-4">
              {p ? (
                <>
                  <Link to={`/produit/${p.product_id}`} className="shrink-0">
                    <img src={p.image_url} alt="" className="size-20 rounded-xl bg-surface-2 object-cover" />
                  </Link>
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <Link to={`/produit/${p.product_id}`} className="truncate font-bold text-ink no-underline hover:underline">
                      {p.name}
                    </Link>
                    <span className="text-sm text-muted tabular-nums">{formatCents(p.price_cents)} l'unité</span>
                  </div>
                  <div className="w-[76px] shrink-0">
                    <select
                      aria-label={`Quantité de ${p.name}`}
                      className={fieldClass}
                      value={line.quantity}
                      onChange={(e) => cart.setQuantity(line.product_id, Number(e.target.value))}
                    >
                      {Array.from({ length: MAX_QUANTITY }, (_, i) => i + 1).map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>
                  </div>
                  <span className="w-[90px] text-right font-extrabold tabular-nums">{formatCents(p.price_cents * line.quantity)}</span>
                </>
              ) : (
                <span className="flex-1 text-muted">{loaded ? 'Produit indisponible' : 'Chargement…'}</span>
              )}
              <Button variant="danger" className="min-h-9 px-3 text-sm" onClick={() => cart.remove(line.product_id)} aria-label="Retirer du panier">
                ✕
              </Button>
            </li>
          ))}
        </ul>

        <aside className="flex h-fit flex-col gap-4 rounded-[22px] border border-line bg-surface p-5">
          <div className="flex items-baseline justify-between">
            <span className="text-muted">
              {articles} article{articles > 1 ? 's' : ''}
            </span>
            <span className="text-[26px] font-extrabold tabular-nums">{formatCents(total)}</span>
          </div>
          <Button variant="primary" disabled>
            Commander
          </Button>
          <p className="m-0 text-xs text-muted">La commande en ligne ouvre très bientôt : votre panier est gardé sur cet appareil.</p>
        </aside>
      </div>
    </section>
  );
}