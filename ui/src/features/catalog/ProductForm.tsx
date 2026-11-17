import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ApiError } from '../../api/http';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { useWarehouseNames } from '../stock/api';
import { toForm, useProduct, useSaveProduct, useSetStatus, type InitialStock } from './api';
import { ATTRIBUTE_FIELDS, CATEGORIES, CATEGORY_LABELS, type Category, type FieldDef } from './categories';
import { emptyAttributes, parseProductForm, type FormValues } from './schema';
import { Section, StockByWarehouse } from './parts';

const NEW: FormValues = { name: '', category: 'Album', price: '', description: '', tags: '', attributes: emptyAttributes('Album') };

/** Ce que le formulaire signale à la page quand il se ferme sur un succès (affiché en toast). */
export type FormOutcome =
  | { kind: 'created' | 'updated'; name: string }
  | { kind: 'archived' | 'reactivated'; name: string; productId: number };

type Props = { productId: number | null; onClose: () => void; onDone: (outcome: FormOutcome) => void };

/** Fiche produit : création (productId null) ou modification, dans une fenêtre <dialog> native. */
export function ProductForm({ productId, onClose, onDone }: Props) {
  const creating = productId === null;
  const dialog = useRef<HTMLDialogElement>(null);
  const existing = useProduct(productId);
  const save = useSaveProduct();
  const setStatus = useSetStatus();

  const [values, setValues] = useState<FormValues | null>(creating ? NEW : null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [stockQty, setStockQty] = useState('');
  const [warehouseId, setWarehouseId] = useState('');

  // Entrepôts pour le stock initial : GET /warehouses/names (id + nom), ouvert à catalog_manager.
  const warehouses = useWarehouseNames(creating);

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  useEffect(() => {
    if (!creating && existing.data && values === null) setValues(toForm(existing.data));
  }, [creating, existing.data, values]);

  const set = (patch: Partial<FormValues>) => setValues((v) => (v ? { ...v, ...patch } : v));
  const setAttr = (key: string, value: string | boolean) =>
    setValues((v) => (v ? { ...v, attributes: { ...v.attributes, [key]: value } } : v));
  const close = () => dialog.current?.close();

  function parseStock(): { stock: InitialStock | null; errors: Record<string, string> } {
    if (!creating || stockQty.trim() === '') return { stock: null, errors: {} };
    const errs: Record<string, string> = {};
    if (!/^\d+$/.test(stockQty.trim())) errs.stockQty = 'Nombre entier attendu';
    if (!/^\d+$/.test(warehouseId)) errs.warehouseId = 'Choisissez un entrepôt';
    return Object.keys(errs).length
      ? { stock: null, errors: errs }
      : { stock: { quantity: Number(stockQty), warehouse_id: Number(warehouseId) }, errors: {} };
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!values) return;
    const parsed = parseProductForm(values);
    const stock = parseStock();
    const allErrors = { ...(parsed.ok ? {} : parsed.errors), ...stock.errors };
    setErrors(allErrors);
    if (!parsed.ok || Object.keys(stock.errors).length) {
      const first = Object.keys(allErrors)[0];
      document.getElementById(`f-${first}`)?.focus();
      return;
    }
    const name = parsed.product.name;
    const args = creating
      ? { mode: 'create' as const, category: values.category, product: parsed.product, initialStock: stock.stock }
      : { mode: 'update' as const, productId, category: values.category, product: parsed.product };
    save.mutate(args, {
      onSuccess: () => {
        onDone({ kind: creating ? 'created' : 'updated', name });
        close();
      },
    });
  }

  function toggleArchive() {
    if (creating || !existing.data) return;
    const archive = existing.data.status === 'active';
    const name = existing.data.name;
    setStatus.mutate(
      { productId, archive },
      {
        onSuccess: () => {
          onDone({ kind: archive ? 'archived' : 'reactivated', name, productId });
          close();
        },
      },
    );
  }

  const archived = existing.data?.status === 'inactive';
  const title = creating ? 'Nouveau produit' : 'Fiche produit';
  const busy = save.isPending || setStatus.isPending;
  const error = save.error ?? setStatus.error;

  return (
    <dialog
      ref={dialog}
      onClose={onClose}
      aria-label={title}
      className="m-auto w-[min(680px,calc(100vw-24px))] rounded-[22px] border border-line bg-surface p-0 text-ink shadow-2xl"
    >
      <form onSubmit={onSubmit} noValidate className="flex max-h-[90vh] flex-col">
        <header className="flex flex-wrap items-center gap-3 border-b border-line px-6 py-4">
          <h2 className="m-0 text-xl font-extrabold">{title}</h2>
          {!creating && <span className="text-sm text-muted">#{productId}</span>}
          {archived && <Badge>Archivé</Badge>}
        </header>

        <div className="flex flex-col gap-5 overflow-y-auto px-6 py-5">
          {!values ? (
            existing.error ? <Notice tone="bad">{existing.error.message}</Notice> : <p className="m-0 text-muted">Chargement…</p>
          ) : (
            <>
              <Section title="Informations générales">
                <Field id="name" label="Nom" required error={errors.name}>
                  <input id="f-name" className={fieldClass} value={values.name} aria-invalid={!!errors.name} onChange={(e) => set({ name: e.target.value })} />
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field id="category" label="Catégorie" required>
                    <select
                      id="f-category"
                      className={fieldClass}
                      value={values.category}
                      onChange={(e) => {
                        const category = e.target.value as Category;
                        // Chaque catégorie a ses propres attributs (D1) : on repart de zéro.
                        set({ category, attributes: emptyAttributes(category) });
                        setErrors({});
                      }}
                    >
                      {CATEGORIES.map((c) => (
                        <option key={c} value={c}>
                          {CATEGORY_LABELS[c]}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field id="price" label="Prix (€)" required error={errors.price}>
                    <input id="f-price" className={fieldClass} inputMode="decimal" placeholder="24,90" value={values.price} aria-invalid={!!errors.price} onChange={(e) => set({ price: e.target.value })} />
                  </Field>
                </div>
              </Section>

              <Section title={`Caractéristiques · ${CATEGORY_LABELS[values.category]}`}>
                <div className="grid gap-4 sm:grid-cols-2">
                  {ATTRIBUTE_FIELDS[values.category].map((f) => (
                    <AttributeInput key={`${values.category}-${f.key}`} field={f} value={values.attributes[f.key]} error={errors[`attributes.${f.key}`]} onChange={(v) => setAttr(f.key, v)} />
                  ))}
                </div>
              </Section>

              <Section title="Présentation">
                <Field id="description" label="Description">
                  <textarea id="f-description" rows={3} className={`${fieldClass} py-2`} value={values.description} onChange={(e) => set({ description: e.target.value })} />
                </Field>
                <Field id="tags" label="Tags" hint="séparés par des virgules">
                  <input id="f-tags" className={fieldClass} value={values.tags} onChange={(e) => set({ tags: e.target.value })} />
                </Field>
              </Section>

              {!creating && (
                <Section title="Stock par entrepôt" hint="Ajustable par les rôles admin et logistics.">
                  <StockByWarehouse productId={productId} />
                </Section>
              )}

              {creating && (
                <Section title="Stock initial (facultatif)" hint="Le catalogue crée la ligne de stock ; si le service Stock échoue, le produit est annulé.">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field id="stockQty" label="Quantité" error={errors.stockQty}>
                      <input id="f-stockQty" className={fieldClass} inputMode="numeric" value={stockQty} aria-invalid={!!errors.stockQty} onChange={(e) => setStockQty(e.target.value)} />
                    </Field>
                    <Field id="warehouseId" label="Entrepôt" error={errors.warehouseId ?? (warehouses.error ? `Liste indisponible : ${warehouses.error.message}` : undefined)}>
                      <select id="f-warehouseId" className={fieldClass} value={warehouseId} disabled={!warehouses.data} aria-invalid={!!errors.warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                        <option value="">{warehouses.isPending ? 'Chargement…' : 'Choisir…'}</option>
                        {warehouses.data?.map((w) => (
                          <option key={w.id} value={w.id}>
                            {w.name}
                          </option>
                        ))}
                      </select>
                    </Field>
                  </div>
                </Section>
              )}
            </>
          )}
          {error && <Notice tone="bad">{errorMessage(error)}</Notice>}
        </div>

        {/* Actions toujours visibles, même quand le formulaire défile. */}
        <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-line px-6 py-4">
          <div>
            {!creating && existing.data && (
              <Button variant={archived ? 'secondary' : 'danger'} disabled={busy} onClick={toggleArchive}>
                {archived ? 'Réactiver' : 'Archiver'}
              </Button>
            )}
          </div>
          <div className="flex gap-2">
            <Button onClick={close}>Annuler</Button>
            <Button type="submit" variant="primary" disabled={!values || busy}>
              {save.isPending ? 'Enregistrement…' : creating ? 'Créer le produit' : 'Enregistrer'}
            </Button>
          </div>
        </footer>
      </form>
    </dialog>
  );
}

function errorMessage(error: Error): string {
  if (!(error instanceof ApiError)) return error.message;
  if (error.code === 'UPSTREAM_ERROR') return "Le service Stock n'a pas répondu : le produit n'a pas été créé.";
  if (error.code === 'VALIDATION_ERROR') return `Refusé par la base : ${error.message}`;
  return error.message;
}

function Field({ id, label, required, hint, error, children }: { id: string; label: string; required?: boolean; hint?: string; error?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={`f-${id}`} className="text-sm font-semibold">
        {label}
        {required && <span aria-hidden="true" className="text-accent"> *</span>}
      </label>
      {children}
      {hint && !error && <span className="text-xs text-muted">{hint}</span>}
      {error && <span className="text-xs font-semibold text-bad">{error}</span>}
    </div>
  );
}

function AttributeInput({ field: f, value, error, onChange }: { field: FieldDef; value: string | boolean | undefined; error?: string; onChange: (v: string | boolean) => void }) {
  const id = `attributes.${f.key}`;
  if (f.kind === 'bool') {
    return (
      <label className="flex min-h-11 cursor-pointer items-center gap-3 self-end rounded-xl border border-line px-3 text-sm font-semibold transition-colors hover:border-muted/60 hover:bg-surface-2">
        <input id={`f-${id}`} type="checkbox" className="size-5 cursor-pointer accent-[var(--accent)]" checked={value === true} onChange={(e) => onChange(e.target.checked)} />
        {f.label}
      </label>
    );
  }
  const common = { id: `f-${id}`, className: fieldClass, value: String(value ?? ''), 'aria-invalid': !!error };
  return (
    <Field id={id} label={f.label} required={f.required} hint={f.hint} error={error}>
      {f.kind === 'enum' ? (
        <select {...common} onChange={(e) => onChange(e.target.value)}>
          <option value="">Choisir…</option>
          {f.options?.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      ) : (
        <input {...common} type={f.kind === 'date' ? 'date' : 'text'} inputMode={f.kind === 'int' ? 'numeric' : undefined} onChange={(e) => onChange(e.target.value)} />
      )}
    </Field>
  );
}