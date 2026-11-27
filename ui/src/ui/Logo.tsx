/**
 * Logo KMarket : un sac de courses marqué d'un K, avec une petite étincelle (K-culture).
 * Couleurs prises dans le thème (accent / accent-ink) : suit automatiquement le mode clair ou sombre.
 */
export function Logo({ className = 'size-9' }: { className?: string }) {
  return (
    <svg viewBox="0 0 40 40" className={className} role="img" aria-label="KMarket">
      <rect width="40" height="40" rx="11" className="fill-accent" />
      {/* poignée du sac */}
      <path d="M15.5 15v-2.5a4.5 4.5 0 0 1 9 0V15" fill="none" strokeWidth="2" strokeLinecap="round" className="stroke-accent-ink" />
      {/* sac */}
      <path d="M10.5 15h19l-1.4 16.2a2.2 2.2 0 0 1-2.2 2h-11.8a2.2 2.2 0 0 1-2.2-2Z" className="fill-accent-ink" />
      {/* K */}
      <path d="M17 19.5v10M23.5 19.5 17.4 24.5l6.1 5" fill="none" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="stroke-accent" />
      {/* étincelle */}
      <path d="M32 5.5c.3 1.6.7 2 2.3 2.3-1.6.3-2 .7-2.3 2.3-.3-1.6-.7-2-2.3-2.3 1.6-.3 2-.7 2.3-2.3Z" className="fill-accent-ink" />
    </svg>
  );
}