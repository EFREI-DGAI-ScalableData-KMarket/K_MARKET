/**
 * Illustration de la page de connexion : la boutique KMarket entourée des quatre métiers
 * qui l'utilisent (un par rôle RBAC), quelques objets K-culture (lightstick, album) et le pass
 * « STAFF » des coulisses. Les objets flottent doucement (coupé si l'utilisateur réduit les animations).
 * Dessin original en SVG : léger, net à toutes les tailles, couleurs du thème (clair / sombre).
 */

const ROLES = [
  { x: 8, y: 110, label: 'Catalogue', role: 'catalog_manager', icon: 'tag', to: [170, 190] },
  { x: 338, y: 96, label: 'Analyse', role: 'analyst', icon: 'chart', to: [310, 176] },
  { x: 8, y: 330, label: 'Logistique', role: 'logistics', icon: 'box', to: [172, 318] },
  { x: 338, y: 316, label: 'Admin', role: 'admin', icon: 'code', to: [308, 318] },
] as const;

type Icon = (typeof ROLES)[number]['icon'];

function RoleIcon({ icon }: { icon: Icon }) {
  const line = { fill: 'none', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' } as const;
  switch (icon) {
    case 'tag':
      return (
        <>
          <path d="M-8-1-1-8h8v8L0 7Z" {...line} className="stroke-accent" />
          <circle cx="3" cy="-4" r="1.6" className="fill-accent" />
        </>
      );
    case 'chart':
      return (
        <>
          <rect x="-8" y="1" width="4" height="7" rx="1" className="fill-accent" />
          <rect x="-2" y="-4" width="4" height="12" rx="1" className="fill-accent" />
          <rect x="4" y="-8" width="4" height="16" rx="1" className="fill-accent" />
        </>
      );
    case 'box':
      return <path d="M-7-3 0-7l7 4v8L0 9l-7-4ZM-7-3l7 4 7-4M0 1v8" {...line} className="stroke-accent" />;
    case 'code':
      return <path d="M-3-6-8 0l5 6M3-6l5 6-5 6" {...line} className="stroke-accent" />;
  }
}

const MOTION = `
@keyframes km-float { 0%, 100% { transform: translateY(0) } 50% { transform: translateY(-7px) } }
@keyframes km-swing { 0%, 100% { transform: rotate(-1.6deg) } 50% { transform: rotate(1.6deg) } }
.km-float { animation: km-float 4.5s ease-in-out infinite; transform-box: fill-box; }
.km-swing { animation: km-swing 3.8s ease-in-out infinite; transform-box: view-box; transform-origin: 66px -320px; }
@media (prefers-reduced-motion: reduce) { .km-float, .km-swing { animation: none } }
`;

/** Pass backstage « STAFF » : l'entrée des coulisses, réservée à l'équipe. */
function BackstagePass() {
  return (
    <g className="km-swing">
      {/* cordon (part du haut du panneau, coupé par son overflow-hidden) */}
      <path d="M40-320 60-8M92-320 72-8" fill="none" strokeWidth="5" strokeLinecap="round" className="stroke-accent" />
      <g transform="translate(0 -22) rotate(-6 66 60)">
        <rect x="58" y="6" width="16" height="16" rx="3" className="fill-ink/70" />
        <rect x="30" y="16" width="72" height="98" rx="9" strokeWidth="1.5" className="fill-surface stroke-line" />
        <path d="M30 25a9 9 0 0 1 9-9h54a9 9 0 0 1 9 9v13H30Z" className="fill-accent" />
        <rect x="56" y="22" width="20" height="5" rx="2.5" className="fill-surface" />
        {/* mini logo : sac + K */}
        <path d="M58 52v-2a4 4 0 0 1 8 0v2" fill="none" strokeWidth="1.6" strokeLinecap="round" className="stroke-ink" />
        <path d="M53.5 52h17l-1.2 13a1.8 1.8 0 0 1-1.8 1.6h-11a1.8 1.8 0 0 1-1.8-1.6Z" className="fill-accent" />
        <path d="M59.5 55.5v8M64.5 55.5l-4.7 4 4.7 4" fill="none" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="stroke-surface" />
        <text x="66" y="84" textAnchor="middle" fontSize="13" fontWeight="900" letterSpacing="1.5" className="fill-ink">
          STAFF
        </text>
        {/* code-barres */}
        {[40, 44, 46, 50, 53, 55, 59, 62, 66, 68, 71, 75, 78, 80, 84, 87, 89].map((x, i) => (
          <rect key={x} x={x} y="94" width={i % 3 === 0 ? 2 : 1} height="12" className="fill-ink/60" />
        ))}
      </g>
    </g>
  );
}

const SPARKLE = 'M0-8C1-2 2-1 8 0 2 1 1 2 0 8-1 2-2 1-8 0-2-1-1-2 0-8Z';
const HEART = 'M0 4C-6-1-6-6-2-6-1-6 0-5 0-4 0-5 1-6 2-6 6-6 6-1 0 4Z';

export function LoginIllustration({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 480 480" className={className} overflow="visible" role="img" aria-label="La boutique KMarket et les métiers qui l'utilisent : catalogue, analyse, logistique et administration">
      <style>{MOTION}</style>
      {/* Décor */}
      <circle cx="240" cy="265" r="190" className="fill-accent/10" />
      <circle cx="440" cy="54" r="16" className="fill-accent/35" />
      <circle cx="452" cy="455" r="9" className="fill-accent/30" />
      <circle cx="26" cy="250" r="6" className="fill-accent/30" />
      {[
        [250, 62, 1],
        [128, 64, 0.7],
        [432, 420, 0.8],
        [58, 432, 0.6],
        [372, 250, 0.5],
      ].map(([x, y, s], i) => (
        <g key={`${x}-${y}`} className="km-float" style={{ animationDelay: `${-i * 0.9}s` }}>
          <path d={SPARKLE} transform={`translate(${x} ${y}) scale(${s})`} className="fill-accent" />
        </g>
      ))}
      {[
        [388, 196, 1.6],
        [118, 296, 1.3],
        [300, 104, 1.1],
      ].map(([x, y, s], i) => (
        <g key={`${x}-${y}`} className="km-float" style={{ animationDelay: `${-i * 1.3 - 0.4}s` }}>
          <path d={HEART} transform={`translate(${x} ${y}) scale(${s})`} className="fill-accent/70" />
        </g>
      ))}

      {/* Liens entre les métiers et la boutique */}
      {ROLES.map((r) => (
        <line
          key={r.role}
          x1={r.x < 240 ? r.x + 132 : r.x}
          y1={r.y + 23}
          x2={r.to[0]}
          y2={r.to[1]}
          strokeWidth="2"
          strokeDasharray="4 6"
          strokeLinecap="round"
          className="stroke-accent/50"
        />
      ))}

      {/* Lightstick */}
      <g className="km-float" style={{ animationDelay: '-1.2s' }}>
      <circle cx="70" cy="214" r="27" className="fill-accent/15" />
      <rect x="65" y="230" width="10" height="56" rx="5" className="fill-ink/70" />
      <circle cx="70" cy="248" r="2.5" className="fill-surface" />
      <circle cx="70" cy="214" r="16" strokeWidth="3" className="fill-accent/45 stroke-accent" />
      <path d={HEART} transform="translate(70 215) scale(1.3)" className="fill-surface" />
      </g>

      {/* Album : le disque dépasse de la pochette */}
      <g className="km-float" style={{ animationDelay: '-2.6s' }}>
      <g transform="translate(410 250) rotate(-12)">
        <circle cx="16" cy="0" r="22" className="fill-ink/80" />
        <circle cx="16" cy="0" r="5" className="fill-accent" />
        <rect x="-26" y="-26" width="52" height="52" rx="6" className="fill-accent" />
        <circle cx="0" cy="0" r="14" fill="none" strokeWidth="2" className="stroke-surface/60" />
        <path d={SPARKLE} transform="scale(0.8)" className="fill-surface" />
      </g>
      </g>

      {/* Boutique */}
      <ellipse cx="240" cy="346" rx="128" ry="9" className="fill-ink/5" />
      <rect x="160" y="205" width="160" height="137" rx="10" strokeWidth="2" className="fill-surface stroke-line" />
      {/* vitrine : un album et un lightstick */}
      <rect x="174" y="228" width="78" height="58" rx="6" className="fill-accent/15" />
      <rect x="184" y="244" width="26" height="26" rx="3" className="fill-accent" />
      <circle cx="197" cy="257" r="7" className="fill-surface" />
      <circle cx="197" cy="257" r="2" className="fill-accent" />
      <rect x="229" y="252" width="5" height="26" rx="2.5" className="fill-ink/60" />
      <circle cx="231.5" cy="248" r="8" strokeWidth="2" className="fill-accent/40 stroke-accent" />
      {/* porte */}
      <rect x="264" y="236" width="42" height="106" rx="6" className="fill-accent/30" />
      <circle cx="297" cy="292" r="3" className="fill-accent" />
      {/* store (auvent) rayé */}
      {Array.from({ length: 10 }, (_, i) => (
        <circle key={i} cx={159 + i * 18} cy="207" r="9" className="fill-accent" />
      ))}
      <rect x="150" y="172" width="180" height="36" rx="8" className="fill-accent" />
      {[168, 204, 240, 276, 312].map((x) => (
        <rect key={x} x={x - 9} y="172" width="18" height="36" className="fill-surface/30" />
      ))}
      {/* enseigne */}
      <rect x="178" y="134" width="124" height="32" rx="16" className="fill-ink" />
      <text x="240" y="156" textAnchor="middle" fontSize="16" fontWeight="800" lang="ko" className="fill-surface">
        케이마켓
      </text>

      {/* Camion de livraison */}
      <g transform="translate(244 412)">
        <path d="M-92-8h-22M-88 2h-14M-96 12h-10" strokeWidth="3" strokeLinecap="round" className="stroke-accent/50" />
        <rect x="-78" y="-26" width="88" height="44" rx="6" strokeWidth="2" className="fill-surface stroke-line" />
        <path d="M10-12h22l14 14v16H10Z" className="fill-accent" />
        <rect x="16" y="-6" width="14" height="10" rx="2" className="fill-surface/70" />
        <text x="-34" y="4" textAnchor="middle" fontSize="15" fontWeight="800" className="fill-accent">K</text>
        <circle cx="-52" cy="20" r="9" className="fill-ink/80" />
        <circle cx="28" cy="20" r="9" className="fill-ink/80" />
        <circle cx="-52" cy="20" r="3" className="fill-surface" />
        <circle cx="28" cy="20" r="3" className="fill-surface" />
      </g>

      {/* Cartes des métiers (rôles RBAC) */}
      {ROLES.map((r) => (
        <g key={r.role} transform={`translate(${r.x} ${r.y})`}>
          <rect width="132" height="46" rx="14" strokeWidth="1.5" className="fill-surface stroke-line" />
          <circle cx="23" cy="23" r="15" className="fill-accent/15" />
          <g transform="translate(23 23)">
            <RoleIcon icon={r.icon} />
          </g>
          <text x="46" y="21" fontSize="14" fontWeight="700" className="fill-ink">
            {r.label}
          </text>
          <text x="46" y="35" fontSize="8.5" fontFamily="ui-monospace, monospace" className="fill-muted">
            {r.role}
          </text>
        </g>
      ))}

      <BackstagePass />
    </svg>
  );
}