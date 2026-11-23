import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { formatCents } from '../../lib/format';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { MAX_QUANTITY, cart } from './cart';
import { TYPE_ALIAS, useProduct } from '../catalog/api';
import { ATTRIBUTE_FIELDS } from '../catalog/categories';
import { attributeValue, categoryLabel } from './labels';

export function ProductPage() {
  const id = Number(useParams().id);
  const valid = Number.isInteger(id) && id > 0;
  const product = useProduct(valid ? id : null);
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  const p = product.data;

  // Inconnu ou archivé : même réponse (on ne révèle pas qu'un produit archivé existe).
  if (!valid || (product.isSuccess && (!p || p.status !== 'active'))) {
    return (
      <section className="flex flex-col items-start gap-3">
        <h1 className="m-0 text-[28px] font-extrabold">Produit introuvable</h1>
        <Link to="/">← Retour à la boutique</Link>
      </section>
    );
  }
  if (product.error) return <Notice tone="bad">{product.error.message}</Notice>;
  if (!p) return <p className="m-0 text-muted">Chargement…</p>;

  // Ordre et libellés de categories.ts ; une valeur d'énumération affiche son libellé (Blu_ray → Blu-ray).
  const details = ATTRIBUTE_FIELDS[p.category].flatMap((f) => {
    const raw = p.attributes[f.key === 'type' ? (TYPE_ALIAS[p.category] ?? 'type') : f.key];
    const shown = f.options?.find((o) => o.value === raw)?.label ?? attributeValue(raw, f.kind);
    return shown ? [[f.label, shown] as const] : [];
  });

  return (
    <section className="flex flex-col gap-5">
      <Link to="/" className="text-sm text-muted">
        ← Boutique
      </Link>
      <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
        <img src={p.image_url} alt={p.name} className="aspect-square w-full rounded-[22px] border border-line bg-surface-2 object-cover" />
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <span className="text-sm font-semibold text-muted">{categoryLabel(p.category)}</span>
            <h1 className="m-0 text-[30px] leading-tight font-extrabold tracking-tight">{p.name}</h1>
            <span className="text-[26px] font-extrabold tabular-nums">{formatCents(p.price_cents)}</span>
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <label className="flex w-[110px] flex-col gap-1 text-xs font-semibold text-muted">
              Quantité
              <select className={fieldClass} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))}>
                {Array.from({ length: MAX_QUANTITY }, (_, i) => i + 1).map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </label>
            <Button
              variant="primary"
              className="flex-1"
              onClick={() => {
                cart.add(p.product_id, quantity);
                setAdded(true);
              }}
            >
              Ajouter au panier
            </Button>
          </div>
          {added && (
            <Notice tone="ok">
              Ajouté au panier. <Link to="/panier">Voir le panier →</Link>
            </Notice>
          )}

          {p.description && <p className="m-0 leading-relaxed">{p.description}</p>}

          {details.length > 0 && (
            <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-2xl bg-surface-2 p-4 text-sm">
              {details.map(([label, value]) => (
                <div key={label} className="contents">
                  <dt className="text-muted">{label}</dt>
                  <dd className="m-0 font-semibold">{value}</dd>
                </div>
              ))}
            </dl>
          )}

          {p.tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {p.tags.map((t) => (
                <Badge key={t}>#{t}</Badge>
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}