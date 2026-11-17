import { useEffect, type ButtonHTMLAttributes, type ReactNode } from 'react';

type Variant = 'primary' | 'secondary' | 'danger';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-accent text-accent-ink border-accent hover:bg-accent-hover hover:border-accent-hover',
  secondary: 'bg-surface text-ink border-line hover:bg-surface-2 hover:border-muted/50',
  danger: 'bg-surface text-bad border-line hover:bg-bad-bg hover:border-bad/40',
};

export function Button({ variant = 'secondary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-full border px-4 font-semibold transition-colors duration-150 motion-safe:active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${className}`}
    />
  );
}

type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'neutral';

const TONES: Record<Tone, string> = {
  ok: 'bg-ok-bg text-ok',
  warn: 'bg-warn-bg text-warn',
  bad: 'bg-bad-bg text-bad',
  info: 'bg-info-bg text-info',
  neutral: 'bg-surface-2 text-muted',
};

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-bold ${TONES[tone]}`}>{children}</span>;
}

export function Notice({ tone = 'info', children }: { tone?: Tone; children: ReactNode }) {
  return <div role={tone === 'bad' ? 'alert' : 'status'} className={`rounded-2xl px-4 py-3 text-sm font-medium ${TONES[tone]}`}>{children}</div>;
}

/** Classes communes des champs de formulaire : survol, focus et erreur visibles. */
export const fieldClass =
  'min-h-11 w-full rounded-xl border border-line bg-surface px-3 text-ink transition-colors duration-150 ' +
  'hover:border-muted/60 focus:border-accent focus:outline-none focus:ring-3 focus:ring-accent/25 ' +
  'disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-bad';

export type ToastData = { id: number; message: string; tone?: 'bad' | 'warn'; action?: { label: string; run: () => void } };

/** Confirmation d'une action (enregistré, archivé…), avec une action d'annulation facultative. Disparaît après 6 s. */
export function Toast({ toast, onDismiss, position = 'bottom' }: { toast: ToastData | null; onDismiss: () => void; position?: 'top' | 'bottom' }) {
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(onDismiss, 6000);
    return () => clearTimeout(t);
  }, [toast, onDismiss]);

  return (
    <div role="status" aria-live="polite" className={`fixed right-5 z-50 max-w-[calc(100vw-40px)] ${position === 'top' ? 'top-5' : 'bottom-5'}`}>
      {toast && (
        <div className="flex items-center gap-4 rounded-2xl bg-ink px-5 py-3 text-sm font-semibold text-bg shadow-lg">
          {toast.tone && <span aria-hidden="true" className={`size-2.5 shrink-0 rounded-full ${toast.tone === 'bad' ? 'bg-bad' : 'bg-warn'}`} />}
          <span>{toast.message}</span>
          {toast.action && (
            <button
              type="button"
              onClick={() => {
                toast.action!.run();
                onDismiss();
              }}
              className="cursor-pointer rounded-full px-2 py-1 font-bold underline underline-offset-2 hover:opacity-80"
            >
              {toast.action.label}
            </button>
          )}
        </div>
      )}
    </div>
  );
}