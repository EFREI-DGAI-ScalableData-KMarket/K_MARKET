import { useCallback, useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { getRoles, getSubject } from '../auth/auth';
import { alertKey, useFiringAlerts } from '../features/observability/api';
import { Toast, type ToastData } from '../ui/ui';
import { useTheme } from './theme';

const TABS = [
  { to: '/', label: "Vue d'ensemble" },
  { to: '/commandes', label: 'Commandes' },
  { to: '/catalogue', label: 'Catalogue' },
  { to: '/logistique', label: 'Logistique' },
  { to: '/observabilite', label: 'Observabilité' },
];

/**
 * Menu latéral à gauche (onglets, puis thème et utilisateur en bas) et contenu à droite.
 * Sous 768 px de large, le menu passe au-dessus du contenu et les onglets défilent en ligne.
 */
export function Layout() {
  const [theme, toggleTheme] = useTheme();
  const roles = getRoles();
  const subject = getSubject();
  const dark = theme === 'dark';
  const navigate = useNavigate();

  // Alertes Prometheus, suivies depuis toutes les pages : compteur dans le menu + notification à chaque nouvelle.
  const alerts = useFiringAlerts();
  const firing = alerts.data ?? [];
  const loaded = alerts.data !== undefined;
  const critical = firing.some((a) => a.labels.severity === 'critical');
  const [toast, setToast] = useState<ToastData | null>(null);
  const dismiss = useCallback(() => setToast(null), []);
  const seen = useRef<Set<string> | null>(null);
  const firingKeys = firing.map(alertKey).join(',');
  useEffect(() => {
    if (!loaded) return;
    const current = new Set(firing.map(alertKey));
    // Première réponse : on mémorise les alertes déjà en cours sans notifier (le compteur suffit).
    if (seen.current === null) {
      seen.current = current;
      return;
    }
    const fresh = firing.filter((a) => !seen.current!.has(alertKey(a)));
    seen.current = current;
    if (fresh.length === 0) return;
    const a = fresh[0];
    setToast({
      id: Date.now(),
      tone: a.labels.severity === 'critical' ? 'bad' : 'warn',
      message: `${a.labels.alertname} : ${a.annotations.summary ?? 'alerte déclenchée'}${fresh.length > 1 ? ` (+${fresh.length - 1})` : ''}`,
      action: { label: 'Voir', run: () => navigate('/observabilite') },
    });
    // firingKeys résume la liste : on ne réagit qu'à un changement d'alertes, pas à chaque requête.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [firingKeys, loaded]);

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <aside className="flex flex-col gap-5 border-b border-line bg-surface px-4 py-5 md:sticky md:top-0 md:h-screen md:w-[248px] md:shrink-0 md:gap-8 md:border-b-0 md:border-r md:py-6">
        <div className="flex items-baseline gap-2 px-2.5">
          <span className="text-[22px] font-extrabold tracking-tight">KMarket</span>
          <span lang="ko" className="text-xs text-muted">케이마켓</span>
        </div>

        <nav aria-label="Onglets" className="flex gap-1 overflow-x-auto md:flex-col md:overflow-visible">
          {TABS.map((t) => (
            <NavLink
              key={t.to}
              to={t.to}
              end={t.to === '/'}
              className={({ isActive }) =>
                `flex min-h-11 shrink-0 items-center rounded-xl px-3.5 font-semibold whitespace-nowrap no-underline transition-colors ${
                  isActive ? 'bg-accent text-accent-ink' : 'text-muted hover:bg-surface-2 hover:text-ink'
                }`
              }
            >
              {t.label}
              {t.to === '/observabilite' && firing.length > 0 && (
                <span
                  aria-label={`${firing.length} alerte${firing.length > 1 ? 's' : ''} en cours`}
                  className={`ml-auto flex min-w-6 items-center justify-center rounded-full px-1.5 text-xs font-extrabold ${critical ? 'bg-bad text-surface' : 'bg-warn text-surface'}`}
                >
                  {firing.length}
                </span>
              )}
            </NavLink>
          ))}
        </nav>

        <div className="flex flex-col gap-2.5 md:mt-auto">
          <button
            type="button"
            onClick={toggleTheme}
            aria-label={dark ? 'Passer en thème clair' : 'Passer en thème sombre'}
            className="flex min-h-11 cursor-pointer items-center gap-2.5 rounded-xl border border-line bg-surface px-3.5 font-semibold text-ink transition-colors hover:bg-surface-2"
          >
            {dark ? <SunIcon /> : <MoonIcon />}
            {dark ? 'Thème clair' : 'Thème sombre'}
          </button>
          <div className="flex items-center gap-2.5 rounded-2xl bg-surface-2 p-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface text-xs font-extrabold uppercase">
              {(roles[0] ?? '?').slice(0, 2)}
            </span>
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-[13px] font-bold">{roles.length ? roles.join(', ') : 'non connecté'}</span>
              {subject && <span className="truncate text-[11px] text-muted">{subject}</span>}
            </div>
          </div>
        </div>
      </aside>

      <Toast toast={toast} onDismiss={dismiss} position="top" />
      <main className="min-w-0 flex-1 px-4 py-6 sm:px-8 sm:py-7">
        <div className="mx-auto max-w-[1240px]">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

function SunIcon() {
  return (
    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  );
}