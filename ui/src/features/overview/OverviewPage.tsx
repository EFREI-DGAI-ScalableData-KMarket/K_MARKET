import { useEffect, useState } from 'react';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { useActiveProductCount, useProductsByIds } from '../catalog/api';
import { CATEGORY_LABELS } from '../catalog/categories';
import { useLowStock } from '../stock/api';
import { LOW_STOCK_THRESHOLD, stockLevel } from '../stock/levels';

export function OverviewPage() {
  const today = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());

  return (
    <section className="flex flex-col gap-5">
      <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Aujourd'hui, {today}</h1>
      <Kpis />
      <Notice tone="neutral">Commandes par heure et alertes en direct : en attente du service Commandes, de Kafka et de Spark.</Notice>
      <LowStock />
    </section>
  );
}

/** Les 4 chiffres du jour du sujet. Seul le taux de rupture a déjà sa source (services Catalogue et Stock). */
function Kpis() {
  const pending = 'En attente : service Commandes et Spark';
  return (
    <section aria-label="Chiffres du jour" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Kpi label="Commandes du jour" value="—" hint={pending} />
      <Kpi label="Chiffre d'affaires" value="—" hint={pending} />
      <Kpi label="Panier moyen" value="—" hint={pending} />
      <StockoutKpi />
    </section>
  );
}

/**
 * Taux de rupture = produits actifs dont le stock total (tous entrepôts) vaut 0 ÷ produits actifs.
 * Les archivés sont exclus des deux côtés : ils ne sont plus en vente. Ex. 1 / 288 = 0,35 % → « 0,3 % ».
 * Un produit actif sans aucune ligne de stock n'est pas compté : /stock/low ne connaît que les lignes existantes.
 */
function StockoutKpi() {
  const active = useActiveProductCount();
  const zero = useLowStock(0, 200);
  const zeroIds = zero.data?.map((s) => s.product_id) ?? [];
  const products = useProductsByIds(zeroIds);
  const out = products.data?.filter((p) => p.status === 'active').length ?? 0;

  const error = active.error ?? zero.error ?? products.error;
  const loading = active.isPending || zero.isPending || (zeroIds.length > 0 && products.isPending);
  if (error) return <Kpi label="Taux de rupture" value="—" hint={error.message} />;
  if (loading || active.data === undefined) return <Kpi label="Taux de rupture" value="…" hint="Calcul en cours" />;

  const rate = active.data ? (out / active.data) * 100 : 0;
  return (
    <Kpi
      label="Taux de rupture"
      value={`${rate.toFixed(1).replace('.', ',')} %`}
      hint={`${out} produit${out > 1 ? 's' : ''} sur ${active.data} en vente ${out > 1 ? "n'ont" : "n'a"} plus de stock`}
      warn={out > 0}
    />
  );
}

function Kpi({ label, value, hint, warn }: { label: string; value: string; hint: string; warn?: boolean }) {
  return (
    <div className={`flex min-h-[150px] flex-col gap-2 rounded-[22px] border p-5 ${warn ? 'border-warn-bg bg-warn-bg text-warn' : 'border-line bg-surface'}`}>
      <span className={`text-sm font-semibold ${warn ? '' : 'text-muted'}`}>{label}</span>
      <span className="mt-auto text-[40px] leading-none font-extrabold tracking-tight">{value}</span>
      <span className={`text-xs ${warn ? 'font-semibold' : 'text-muted'}`}>{hint}</span>
    </div>
  );
}

function LowStock() {
  // Fourchette saisie (en texte, pour laisser l'utilisateur effacer un champ pendant la frappe).
  const [minInput, setMinInput] = useState('0');
  const [maxInput, setMaxInput] = useState(String(LOW_STOCK_THRESHOLD));
  const [range, setRange] = useState({ min: 0, max: LOW_STOCK_THRESHOLD });
  const rangeError = parseRange(minInput, maxInput).error;

  // On n'interroge qu'une fois la saisie valide et stable (300 ms).
  useEffect(() => {
    const parsed = parseRange(minInput, maxInput);
    if (parsed.error) return;
    const t = setTimeout(() => setRange({ min: parsed.min, max: parsed.max }), 300);
    return () => clearTimeout(t);
  }, [minInput, maxInput]);

  // Le service Stock filtre « ≤ max », trié du plus bas au plus haut ; le minimum est appliqué ici.
  // Il ne connaît pas le statut catalogue : les premières lignes peuvent être des produits archivés ou
  // absents du catalogue. On en demande donc 200 (le maximum de productsByIds) avant de les écarter.
  const low = useLowStock(range.max, 200);
  const inRange = (low.data ?? []).filter((s) => s.total_quantity >= range.min);
  const ids = inRange.map((s) => s.product_id);
  const products = useProductsByIds(ids);
  const byId = new Map(products.data?.map((p) => [p.product_id, p]));
  const rows = inRange
    .map((s) => ({ stock: s, product: byId.get(s.product_id) }))
    .filter((r) => r.product?.status === 'active');

  const out = rows.filter((r) => r.stock.total_quantity === 0).length;
  const truncated = (low.data?.length ?? 0) >= 200;
  const error = low.error ?? products.error;
  const loading = low.isPending || (ids.length > 0 && products.isPending);
  const isDefault = range.min === 0 && range.max === LOW_STOCK_THRESHOLD;

  return (
    <section className="flex flex-col gap-3 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="m-0 text-[17px] font-bold">{isDefault ? 'Ruptures et stocks bas' : `Stock entre ${range.min} et ${range.max}`}</h2>
          <span className="text-xs text-muted">
            {rows.length} produit{rows.length > 1 ? 's' : ''} actif{rows.length > 1 ? 's' : ''}
            {out > 0 && ` · dont ${out} en rupture`}
          </span>
        </div>
        <fieldset className="m-0 flex flex-wrap items-end gap-2 border-0 p-0">
          <legend className="sr-only">Fourchette de stock</legend>
          <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
            Stock min.
            <input className={`${fieldClass} w-24`} inputMode="numeric" value={minInput} aria-invalid={!!rangeError} onChange={(e) => setMinInput(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
            Stock max.
            <input className={`${fieldClass} w-24`} inputMode="numeric" value={maxInput} aria-invalid={!!rangeError} onChange={(e) => setMaxInput(e.target.value)} />
          </label>
          {!isDefault && (
            <Button
              onClick={() => {
                setMinInput('0');
                setMaxInput(String(LOW_STOCK_THRESHOLD));
              }}
            >
              Réinitialiser
            </Button>
          )}
        </fieldset>
      </div>
      {rangeError && <span className="text-xs font-semibold text-bad">{rangeError}</span>}
      {truncated && (
        <Notice tone="warn">Plus de 200 lignes de stock sous {range.max} : seules les 200 plus basses sont examinées.</Notice>
      )}
      {error ? (
        <Notice tone="bad">{error.message}</Notice>
      ) : loading ? (
        <p className="m-0 text-muted">Chargement…</p>
      ) : rows.length === 0 ? (
        <p className="m-0 text-muted">Aucun produit actif dans cette fourchette.</p>
      ) : (
        <ul className="m-0 flex max-h-[440px] list-none flex-col gap-2 overflow-y-auto p-0">
          {rows.map(({ stock, product }) => (
            <li key={stock.product_id} className="flex flex-wrap items-center gap-3 rounded-2xl bg-surface-2 px-4 py-3">
              <div className="flex min-w-[200px] flex-1 flex-col">
                <span className="font-semibold">{product!.name}</span>
                <span className="text-xs text-muted">
                  {CATEGORY_LABELS[product!.category]} · #{stock.product_id}
                </span>
              </div>
              <span className="text-sm font-bold">{stock.total_quantity === 0 ? 'plus aucune unité' : `${stock.total_quantity} en stock`}</span>
              <LevelBadge quantity={stock.total_quantity} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function LevelBadge({ quantity }: { quantity: number }) {
  const level = stockLevel(quantity);
  if (level === 'out') return <Badge tone="bad">Rupture</Badge>;
  if (level === 'low') return <Badge tone="warn">Stock bas</Badge>;
  return null;
}

function parseRange(minInput: string, maxInput: string): { min: number; max: number; error?: string } {
  const isInt = (v: string) => /^\d+$/.test(v.trim());
  if (!isInt(minInput) || !isInt(maxInput)) return { min: 0, max: 0, error: 'Nombres entiers attendus.' };
  const min = Number(minInput);
  const max = Number(maxInput);
  if (min > max) return { min, max, error: 'Le minimum doit être inférieur ou égal au maximum.' };
  return { min, max };
}