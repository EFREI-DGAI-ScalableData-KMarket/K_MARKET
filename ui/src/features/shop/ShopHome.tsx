import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { formatCents } from '../../lib/format';
import { Button, Notice, fieldClass } from '../../ui/ui';
import { useProducts, type ProductSummary } from '../catalog/api';
import { cart } from './cart';
import { CATEGORIES, categoryLabel } from './labels';

/** Catalogue public : produits actifs, filtre par catégorie et recherche (gardés dans l'URL, donc partageables). */
export function ShopHome() {
  const [params, setParams] = useSearchParams();
  const category = CATEGORIES.find((c) => c.value === params.get('categorie'))?.value ?? null;
  const search = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const [draft, setDraft] = useState(search);

  // Statut forcé à « active » : même un membre de l'équipe connecté ne voit pas les archivés dans la boutique.
  const products = useProducts({ status: 'active', category, search: search || null }, page);
  const items = products.data?.items ?? [];

  function update(next: Record<string, string | null>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v);
      else p.delete(k);
    }
    if (!('page' in next)) p.delete('page'); // nouveau filtre : retour à la première page
    setParams(p);
  }

  function onSearch(e: FormEvent) {
    e.preventDefault();
    update({ q: draft.trim() || null });
  }

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="m-0 text-[32px] font-extrabold tracking-tight">
          어서 오세요 <span className="text-muted">· Bienvenue</span>
        </h1>
        <p className="m-0 text-muted">Albums, lightsticks, merch, dramas et K-beauty.</p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex gap-1.5 overflow-x-auto" aria-label="Catégories">
          {[{ value: null, label: 'Tout' }, ...CATEGORIES].map((c) => (
            <button
              key={c.label}
              type="button"
              aria-pressed={category === c.value}
              onClick={() => update({ categorie: c.value })}
              className={`min-h-10 shrink-0 cursor-pointer rounded-full px-4 text-sm font-semibold whitespace-nowrap transition-colors ${
                category === c.value ? 'bg-accent text-accent-ink' : 'bg-surface-2 text-muted hover:text-ink'
              }`}
            >
              {c.label}
            </button>
          ))}
        </div>
        <form role="search" onSubmit={onSearch} className="flex gap-2">
          <input
            type="search"
            className={`${fieldClass} w-[220px]`}
            placeholder="Artiste, série, marque…"
            aria-label="Rechercher un produit"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <Button type="submit">Rechercher</Button>
        </form>
      </div>

      {products.error && <Notice tone="bad">{products.error.message}</Notice>}

      {products.isPending ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : items.length === 0 ? (
        <Notice tone="neutral">Aucun produit ne correspond.</Notice>
      ) : (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
          {items.map((p) => (
            <ProductTile key={p.product_id} product={p} />
          ))}
        </div>
      )}

      {(page > 0 || products.data?.has_more) && (
        <nav aria-label="Pages du catalogue" className="flex items-center justify-center gap-3">
          <Button disabled={page === 0} onClick={() => update({ page: page > 1 ? String(page - 1) : null })}>
            ← Précédent
          </Button>
          <span className="min-w-[80px] text-center text-sm font-semibold text-muted">Page {page + 1}</span>
          <Button disabled={!products.data?.has_more} onClick={() => update({ page: String(page + 1) })}>
            Suivant →
          </Button>
        </nav>
      )}
    </section>
  );
}

function ProductTile({ product: p }: { product: ProductSummary }) {
  const [added, setAdded] = useState(false);
  useEffect(() => {
    if (!added) return;
    const t = setTimeout(() => setAdded(false), 1500);
    return () => clearTimeout(t);
  }, [added]);

  return (
    <article className="flex flex-col overflow-hidden rounded-[22px] border border-line bg-surface">
      <Link to={`/produit/${p.product_id}`} className="flex flex-1 flex-col text-ink no-underline">
        <img src={p.image_url} alt="" loading="lazy" className="aspect-square w-full bg-surface-2 object-cover" />
        <div className="flex flex-1 flex-col gap-1 p-4">
          <span className="text-xs font-semibold text-muted">{categoryLabel(p.category)}</span>
          <h2 className="m-0 text-[15px] leading-snug font-bold">{p.name}</h2>
          <span className="mt-auto pt-2 text-lg font-extrabold tabular-nums">{formatCents(p.price_cents)}</span>
        </div>
      </Link>
      <div className="px-4 pb-4">
        <Button
          variant="primary"
          className="w-full"
          onClick={() => {
            cart.add(p.product_id);
            setAdded(true);
          }}
        >
          {added ? 'Ajouté ✓' : 'Ajouter au panier'}
        </Button>
      </div>
    </article>
  );
}