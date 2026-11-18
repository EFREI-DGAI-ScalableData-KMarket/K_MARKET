import { useEffect, useRef } from 'react';
import { formatCents } from '../../lib/format';
import { Badge, Button, Notice } from '../../ui/ui';
import { toForm, useProduct } from './api';
import { ATTRIBUTE_FIELDS, CATEGORY_LABELS, type FieldDef } from './categories';
import { Section, StockByWarehouse } from './parts';

const DATE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' });

/** Fiche produit en consultation, pour les rôles sans droit d'écriture (analyst, logistics). */
export function ProductView({ productId, onClose }: { productId: number; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const product = useProduct(productId);
  const p = product.data;

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  // Même conversion que le formulaire : les alias GraphQL (album_type…) redeviennent `type`.
  const attrs = p ? toForm(p).attributes : {};

  return (
    <dialog
      ref={dialog}
      onClose={onClose}
      aria-label="Fiche produit"
      className="m-auto w-[min(680px,calc(100vw-24px))] rounded-[22px] border border-line bg-surface p-0 text-ink shadow-2xl"
    >
      <div className="flex max-h-[90vh] flex-col">
        <header className="flex flex-wrap items-center gap-3 border-b border-line px-6 py-4">
          <h2 className="m-0 text-xl font-extrabold">Fiche produit</h2>
          <span className="text-sm text-muted">#{productId}</span>
          {p?.status === 'inactive' && <Badge>Archivé</Badge>}
          <span className="ml-auto">
            <Badge tone="neutral">Lecture seule</Badge>
          </span>
        </header>

        <div className="flex flex-col gap-6 overflow-y-auto px-6 py-5">
          {product.error ? (
            <Notice tone="bad">{product.error.message}</Notice>
          ) : !p ? (
            <p className="m-0 text-muted">{product.isPending ? 'Chargement…' : 'Produit introuvable.'}</p>
          ) : (
            <>
              <div className="flex gap-4">
                <img src={p.image_url} alt="" className="aspect-[4/3] w-36 shrink-0 rounded-2xl object-cover" />
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted">{CATEGORY_LABELS[p.category]}</span>
                  <span className="text-lg font-bold leading-snug">{p.name}</span>
                  <span className="text-xl font-extrabold">{formatCents(p.price_cents)}</span>
                </div>
              </div>

              <Section title={`Caractéristiques · ${CATEGORY_LABELS[p.category]}`}>
                <dl className="m-0 grid gap-x-6 gap-y-3 sm:grid-cols-2">
                  {ATTRIBUTE_FIELDS[p.category].map((f) => (
                    <div key={f.key} className="flex flex-col gap-0.5">
                      <dt className="text-xs font-semibold text-muted">{f.label}</dt>
                      <dd className="m-0 font-medium">{display(f, attrs[f.key])}</dd>
                    </div>
                  ))}
                </dl>
              </Section>

              {(p.description || p.tags.length > 0) && (
                <Section title="Présentation">
                  {p.description && <p className="m-0 whitespace-pre-line">{p.description}</p>}
                  {p.tags.length > 0 && (
                    <div className="flex flex-wrap gap-1.5">
                      {p.tags.map((t) => (
                        <Badge key={t} tone="info">
                          {t}
                        </Badge>
                      ))}
                    </div>
                  )}
                </Section>
              )}

              <Section title="Stock par entrepôt">
                <StockByWarehouse productId={productId} />
              </Section>
            </>
          )}
        </div>

        <footer className="flex justify-end border-t border-line px-6 py-4">
          <Button onClick={() => dialog.current?.close()}>Fermer</Button>
        </footer>
      </div>
    </dialog>
  );
}

/** Valeur d'attribut lisible : libellé d'enum, Oui/Non, date en toutes lettres, « — » si vide. */
function display(f: FieldDef, value: string | boolean | undefined): string {
  if (f.kind === 'bool') return value === true ? 'Oui' : 'Non';
  if (value === undefined || value === '') return '—';
  const v = String(value);
  if (f.kind === 'enum') return f.options?.find((o) => o.value === v)?.label ?? v;
  if (f.kind === 'date') {
    const d = new Date(`${v}T00:00:00`);
    return Number.isNaN(d.getTime()) ? v : DATE.format(d);
  }
  return v;
}