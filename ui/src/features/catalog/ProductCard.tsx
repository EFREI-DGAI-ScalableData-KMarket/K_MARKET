import { useEffect, useRef, useState } from 'react';
import { formatCents } from '../../lib/format';
import { Badge } from '../../ui/ui';
import { stockLevel } from '../stock/levels';
import type { ProductSummary } from './api';
import { CATEGORY_LABELS } from './categories';

type Props = {
  product: ProductSummary;
  /** value : quantité totale, null = pas de ligne de stock, undefined = pas encore chargé */
  stock: { value: number | null | undefined; loading: boolean; error: Error | null };
  /** Ouvre la fiche (modifiable ou en lecture seule selon le rôle). */
  onOpen?: () => void;
  /** Absent pour un rôle en lecture seule : pas de menu « ⋯ ». */
  onToggleStatus?: () => void;
  busy?: boolean;
};

/** Carte produit. Clic : ouvre la fiche. Menu « ⋯ » : Modifier, Archiver / Réactiver. */
export function ProductCard({ product: p, stock, onOpen, onToggleStatus, busy }: Props) {
  const archived = p.status === 'inactive';
  const content = (
    <>
      <div className="relative">
        {/* Visuel SVG servi par le catalogue, sans jeton (une balise <img> n'en envoie pas). */}
        <img src={p.image_url} alt="" loading="lazy" className={`aspect-[4/3] w-full bg-surface-2 object-cover ${archived ? 'opacity-55' : ''}`} />
        {archived && (
          <span className="absolute left-3 top-3">
            <Badge>Archivé</Badge>
          </span>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-1 p-4">
        <span className="text-xs font-semibold uppercase tracking-wide text-muted">{CATEGORY_LABELS[p.category]}</span>
        <h2 className="m-0 line-clamp-2 text-[15px] font-bold leading-snug">{p.name}</h2>
        <div className="mt-auto flex items-center justify-between gap-2 pt-2">
          <span className="text-lg font-extrabold">{formatCents(p.price_cents)}</span>
          <StockLabel stock={stock} />
        </div>
      </div>
    </>
  );

  if (!onOpen) {
    return <article className="flex flex-col overflow-hidden rounded-[22px] border border-line bg-surface">{content}</article>;
  }

  return (
    // Le menu est un frère du bouton principal (un bouton ne peut pas en contenir un autre).
    <article className="group relative flex rounded-[22px] border border-line bg-surface transition-[border-color,box-shadow,transform] duration-150 hover:border-accent hover:shadow-lg focus-within:border-accent motion-safe:hover:-translate-y-0.5">
      <button
        type="button"
        onClick={onOpen}
        aria-label={`Ouvrir la fiche : ${p.name}`}
        className="flex w-full cursor-pointer flex-col overflow-hidden rounded-[21px] border-0 bg-transparent p-0 text-left font-[inherit] text-ink focus-visible:outline-offset-[-2px]"
      >
        {content}
      </button>
      {onToggleStatus && <CardMenu archived={archived} busy={!!busy} onEdit={onOpen} onToggleStatus={onToggleStatus} />}
    </article>
  );
}

function CardMenu({ archived, busy, onEdit, onToggleStatus }: { archived: boolean; busy: boolean; onEdit: () => void; onToggleStatus: () => void }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  // Fermeture : clic à l'extérieur ou Échap (le focus revient sur le bouton « ⋯ »).
  useEffect(() => {
    if (!open) return;
    const onPointer = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    root.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const choose = (action: () => void) => () => {
    setOpen(false);
    action();
  };

  const item =
    'flex w-full cursor-pointer items-center gap-2.5 rounded-xl border-0 bg-transparent px-3 py-2.5 text-left font-[inherit] text-sm font-semibold hover:bg-surface-2 focus-visible:bg-surface-2';

  return (
    <div ref={root} className="absolute right-3 top-3">
      <button
        ref={trigger}
        type="button"
        aria-label="Actions"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex size-10 cursor-pointer items-center justify-center rounded-full border-0 bg-surface/90 text-ink shadow-sm transition-colors hover:bg-surface"
      >
        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
          <circle cx="5" cy="12" r="2" />
          <circle cx="12" cy="12" r="2" />
          <circle cx="19" cy="12" r="2" />
        </svg>
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-12 z-10 flex min-w-[180px] flex-col rounded-2xl border border-line bg-surface p-1.5 shadow-xl">
          <button type="button" role="menuitem" className={`${item} text-ink`} onClick={choose(onEdit)}>
            <PencilIcon />
            Modifier
          </button>
          <button type="button" role="menuitem" disabled={busy} className={`${item} ${archived ? 'text-ink' : 'text-bad'} disabled:opacity-50`} onClick={choose(onToggleStatus)}>
            {archived ? <UndoIcon /> : <ArchiveIcon />}
            {archived ? 'Réactiver' : 'Archiver'}
          </button>
        </div>
      )}
    </div>
  );
}

function StockLabel({ stock }: { stock: Props['stock'] }) {
  if (stock.error) return <span className="text-sm text-muted">Stock —</span>;
  if (stock.loading || stock.value === undefined) return <span className="text-sm text-muted">…</span>;
  switch (stockLevel(stock.value)) {
    case 'none':
      return <span className="text-sm text-muted">Sans stock</span>;
    case 'out':
      return <Badge tone="bad">Rupture</Badge>;
    case 'low':
      return <Badge tone="warn">Stock bas · {stock.value}</Badge>;
    default:
      return <span className="text-sm text-muted">{stock.value} en stock</span>;
  }
}

const iconProps = { 'aria-hidden': true, width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' } as const;

function PencilIcon() {
  return (
    <svg {...iconProps}>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  );
}

function ArchiveIcon() {
  return (
    <svg {...iconProps}>
      <rect x="3" y="4" width="18" height="5" rx="1" />
      <path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9" />
      <path d="M10 13h4" />
    </svg>
  );
}

function UndoIcon() {
  return (
    <svg {...iconProps}>
      <path d="M3 7v6h6" />
      <path d="M21 17a9 9 0 0 0-15-6.7L3 13" />
    </svg>
  );
}