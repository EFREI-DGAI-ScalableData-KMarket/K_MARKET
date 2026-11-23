import { Link, NavLink, Outlet } from 'react-router';
import { GESTION } from '../../app/paths';
import { useTheme } from '../../app/theme';
import { Logo } from '../../ui/Logo';
import { useCart } from './cart';

/** Boutique publique : en-tête (logo, thème, panier), contenu, pied de page avec l'accès à l'espace staff. */
export function ShopLayout() {
  const [theme, toggleTheme] = useTheme();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  const dark = theme === 'dark';

  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-line bg-surface">
        <div className="mx-auto flex max-w-[1240px] items-center gap-4 px-4 py-3 sm:px-8">
          <Link to="/" className="flex items-center gap-2.5 text-ink no-underline">
            <Logo className="size-8 shrink-0" />
            <span className="text-[22px] font-extrabold tracking-tight">KMarket</span>
            <span lang="ko" className="hidden text-xs text-muted sm:inline">케이마켓</span>
          </Link>
          <nav aria-label="Boutique" className="ml-auto flex items-center gap-1.5">
            <button
              type="button"
              onClick={toggleTheme}
              aria-label={dark ? 'Passer en thème clair' : 'Passer en thème sombre'}
              className="flex size-11 cursor-pointer items-center justify-center rounded-full text-lg text-muted transition-colors hover:bg-surface-2 hover:text-ink"
            >
              {dark ? '☀' : '☾'}
            </button>
            <NavLink
              to="/panier"
              className={({ isActive }) =>
                `flex min-h-11 items-center gap-2 rounded-full px-4 font-semibold no-underline transition-colors ${
                  isActive ? 'bg-accent text-accent-ink' : 'text-ink hover:bg-surface-2'
                }`
              }
            >
              Panier
              {count > 0 && (
                <span aria-label={`${count} article${count > 1 ? 's' : ''}`} className="flex min-w-6 items-center justify-center rounded-full bg-ink px-1.5 text-xs font-extrabold text-bg">
                  {count}
                </span>
              )}
            </NavLink>
          </nav>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1240px] flex-1 px-4 py-6 sm:px-8 sm:py-8">
        <Outlet />
      </main>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-[1240px] flex-wrap items-center justify-between gap-2 px-4 py-4 text-xs text-muted sm:px-8">
          <span>KMarket · K-pop, K-drama et K-beauty</span>
          <Link to={GESTION} className="text-muted">
            Espace staff
          </Link>
        </div>
      </footer>
    </div>
  );
}