import { useCallback, useEffect, useState } from 'react';
import { getRoles } from '../../auth/auth';
import { can, reservedTo } from '../../auth/permissions';
import { Button, Notice, Toast, fieldClass, type ToastData } from '../../ui/ui';
import { Locked } from '../../ui/Locked';
import { useStockTotals } from '../stock/api';
import { PAGE_SIZE, useProducts, useSetStatus, type ProductFilter, type ProductStatus } from './api';
import { CATEGORIES, CATEGORY_LABELS, type Category } from './categories';
import { ProductCard } from './ProductCard';
import { ProductForm, type FormOutcome } from './ProductForm';
import { ProductView } from './ProductView';
import { parseEurosToCents } from '../../lib/format';

/**
 * Colonnes fixes (1, 2, 3 ou 4 selon la largeur) et PAGE_SIZE = 24, multiple de chacune :
 * une page pleine remplit toujours toutes ses lignes ; seule la dernière page peut être incomplète.
 */
const GRID = 'grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4';

export function CatalogPage() {
  const canWrite = can(getRoles(), 'catalog.write');

  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<Category | ''>('');
  const [status, setStatus] = useState<ProductStatus | ''>('active');
  const [minPriceInput, setMinPriceInput] = useState('');
  const [maxPriceInput, setMaxPriceInput] = useState('');
  const [prices, setPrices] = useState<{ min: number | null; max: number | null }>({ min: null, max: null });
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [toast, setToast] = useState<ToastData | null>(null);

  // Recherche plein texte (index MongoDB) : on attend une pause de frappe avant d'interroger.
  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  // Prix saisis en euros, envoyés en centimes ; un champ vide = pas de borne. Pause de frappe de 300 ms.
  const minCents = minPriceInput.trim() ? parseEurosToCents(minPriceInput) : null;
  const maxCents = maxPriceInput.trim() ? parseEurosToCents(maxPriceInput) : null;
  const minInvalid = minPriceInput.trim() !== '' && minCents === null;
  const maxInvalid = maxPriceInput.trim() !== '' && maxCents === null;
  const rangeInvalid = minCents !== null && maxCents !== null && minCents > maxCents;
  useEffect(() => {
    if (minInvalid || maxInvalid || rangeInvalid) return;
    const t = setTimeout(() => {
      setPrices({ min: minCents, max: maxCents });
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [minCents, maxCents, minInvalid, maxInvalid, rangeInvalid]);

  const filter: ProductFilter = {
    category: category || null,
    status: status || null,
    search: search || null,
    min_price_cents: prices.min,
    max_price_cents: prices.max,
  };
  const products = useProducts(filter, page);
  const items = products.data?.items ?? [];
  const stock = useStockTotals(items.map((p) => p.product_id));
  const statusMut = useSetStatus();

  const total = products.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const dismissToast = useCallback(() => setToast(null), []);

  /** Archiver / réactiver depuis le menu « ⋯ » d'une carte, avec le même message que depuis la fiche. */
  function toggleStatus(productId: number, name: string, archive: boolean) {
    statusMut.mutate(
      { productId, archive },
      { onSuccess: () => onFormDone({ kind: archive ? 'archived' : 'reactivated', name, productId }) },
    );
  }

  function onFormDone(outcome: FormOutcome) {
    const id = Date.now();
    switch (outcome.kind) {
      case 'created':
        return setToast({ id, message: `« ${outcome.name} » créé.` });
      case 'updated':
        return setToast({ id, message: 'Modifications enregistrées.' });
      case 'archived':
        return setToast({
          id,
          message: `« ${outcome.name} » archivé.`,
          action: { label: 'Annuler', run: () => statusMut.mutate({ productId: outcome.productId, archive: false }) },
        });
      case 'reactivated':
        return setToast({ id, message: `« ${outcome.name} » réactivé.` });
    }
  }

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Catalogue</h1>
          <p className="m-0 text-sm text-muted">{products.data ? `${total} produit${total > 1 ? 's' : ''}` : ' '}</p>
        </div>
        {/* Visible pour tous, grisé sans le droit (D9). */}
        <Locked locked={!canWrite} reason={reservedTo('catalog.write')}>
          <Button variant="primary" disabled={!canWrite} onClick={() => setEditing('new')}>
            + Nouveau produit
          </Button>
        </Locked>
      </div>

      <div className="flex flex-wrap gap-3 rounded-[22px] border border-line bg-surface p-4">
        <label className="flex min-w-[220px] flex-[2] flex-col gap-1.5 text-sm font-semibold">
          Rechercher
          <input type="search" className={fieldClass} placeholder="Nom ou description" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
        </label>
        <label className="flex min-w-[160px] flex-1 flex-col gap-1.5 text-sm font-semibold">
          Catégorie
          <select
            className={fieldClass}
            value={category}
            onChange={(e) => {
              setCategory(e.target.value as Category | '');
              setPage(0);
            }}
          >
            <option value="">Toutes</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABELS[c]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-w-[160px] flex-1 flex-col gap-1.5 text-sm font-semibold">
          Statut
          <select
            className={fieldClass}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as ProductStatus | '');
              setPage(0);
            }}
          >
            <option value="active">Actifs</option>
            <option value="inactive">Archivés</option>
            <option value="">Tous</option>
          </select>
        </label>
        <div className="flex min-w-[220px] flex-1 flex-col gap-1.5">
          <span id="price-label" className="text-sm font-semibold">
            Prix (€)
          </span>
          <div role="group" aria-labelledby="price-label" className="flex items-center gap-2">
            <input className={fieldClass} inputMode="decimal" placeholder="min" aria-label="Prix minimum" aria-invalid={minInvalid || rangeInvalid} value={minPriceInput} onChange={(e) => setMinPriceInput(e.target.value)} />
            <span className="text-muted">–</span>
            <input className={fieldClass} inputMode="decimal" placeholder="max" aria-label="Prix maximum" aria-invalid={maxInvalid || rangeInvalid} value={maxPriceInput} onChange={(e) => setMaxPriceInput(e.target.value)} />
          </div>
        </div>
      </div>
      {(minInvalid || maxInvalid || rangeInvalid) && (
        <span className="-mt-3 text-xs font-semibold text-bad">
          {rangeInvalid ? 'Le prix minimum dépasse le maximum.' : 'Prix en euros, ex. 24,90.'}
        </span>
      )}

      {!canWrite && <Notice>Lecture seule : cliquez sur un produit pour voir sa fiche. Seuls les rôles admin et catalog_manager modifient le catalogue.</Notice>}
      {products.error && <Notice tone="bad">{products.error.message}</Notice>}
      {stock.error && <Notice tone="warn">Stock indisponible : {stock.error.message}</Notice>}
      {statusMut.error && <Notice tone="bad">{statusMut.error.message}</Notice>}

      {products.isPending ? (
        <p className="text-muted">Chargement…</p>
      ) : items.length === 0 && !products.error ? (
        <p className="text-muted">Aucun produit ne correspond.</p>
      ) : (
        <div className={GRID}>
          {items.map((p) => (
            <ProductCard
              key={p.product_id}
              product={p}
              stock={{ value: stock.totals.get(p.product_id), loading: stock.isPending, error: stock.error }}
              onOpen={() => setEditing(p.product_id)}
              onToggleStatus={canWrite ? () => toggleStatus(p.product_id, p.name, p.status === 'active') : undefined}
              busy={statusMut.isPending && statusMut.variables?.productId === p.product_id}
            />
          ))}
        </div>
      )}

      {pageCount > 1 && (
        <nav aria-label="Pagination" className="flex flex-wrap items-center justify-center gap-3">
          <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            ← Précédent
          </Button>
          <span className="min-w-[110px] text-center text-sm font-semibold text-muted">
            Page {page + 1} / {pageCount}
          </span>
          <Button disabled={!products.data?.has_more} onClick={() => setPage((p) => p + 1)}>
            Suivant →
          </Button>
        </nav>
      )}

      {editing !== null &&
        (canWrite ? (
          <ProductForm key={String(editing)} productId={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={onFormDone} />
        ) : (
          editing !== 'new' && <ProductView key={editing} productId={editing} onClose={() => setEditing(null)} />
        ))}
      <Toast toast={toast} onDismiss={dismissToast} />
    </section>
  );
}