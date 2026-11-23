import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { getRoles, login, logout } from '../auth/auth';
import { isInternal } from '../auth/permissions';
import { useTheme } from '../app/theme';
import { Logo } from '../ui/Logo';
import { LoginIllustration } from './LoginIllustration';
import { GESTION } from '../app/paths';

/** Comptes créés par seed/seed.py (mot de passe : DEMO_PASSWORD). Absents du build de production. */
const DEMO_ACCOUNTS = import.meta.env.DEV
  ? [
      { label: 'Admin', email: 'admin@market.test' },
      { label: 'Catalogue', email: 'catalog@market.test' },
      { label: 'Analyse', email: 'analyst@market.test' },
      { label: 'Logistique', email: 'logistics@market.test' },
    ]
  : [];

/** Un compte client (rôle customer) n'entre pas dans le back-office (D9). */
const CLIENT_MESSAGE = "Ce compte est un compte client : cet espace est réservé à l'équipe KMarket.";

const fieldCls =
  'min-h-11 w-full rounded-xl border border-line bg-surface px-3.5 text-ink outline-none transition-colors focus:border-accent';

/** Retour après connexion : seulement vers une page de l'espace de gestion (pas de redirection ouverte). */
function safeNext(next: string | null): string {
  return next && (next === GESTION || next.startsWith(`${GESTION}/`) || next.startsWith(`${GESTION}?`)) ? next : GESTION;
}
export function LoginPage() {
  useTheme(); // même thème que le reste de l'application
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(params.get('reason') === 'client' ? CLIENT_MESSAGE : null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      await login(email.trim(), password);
      if (!isInternal(getRoles())) {
        await logout();
        setError(CLIENT_MESSAGE);
        return;
      }
      // Retour à la page demandée ; uniquement un chemin interne (pas de redirection vers un autre site).
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connexion impossible.');
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="grid min-h-screen bg-surface md:grid-cols-[1.1fr_1fr]">
      {/* Illustration : masquée sur petit écran, le formulaire passe en premier */}
      <section className="relative hidden flex-col items-center justify-center gap-6 overflow-hidden bg-linear-to-br from-accent/5 via-surface-2 to-accent/25 px-10 py-12 md:flex">
        <LoginIllustration className="w-full max-w-[460px]" />
        <div className="max-w-md text-center">
          <p lang="ko" className="text-4xl font-extrabold tracking-tight">
            어서 오세요!
          </p>
          <p className="mt-2 text-muted">Bienvenue dans les coulisses de KMarket.</p>
        </div>
        {/* Séparation en vague avec le panneau du formulaire */}
        <svg
          aria-hidden="true"
          viewBox="0 0 60 800"
          preserveAspectRatio="none"
          className="absolute inset-y-0 right-0 h-full w-14 fill-surface"
        >
          <path d="M60 0H34C6 110 52 210 26 330S4 560 34 660 22 760 30 800H60Z" />
        </svg>
      </section>

      <section className="flex items-center justify-center px-4 py-12">
        <form onSubmit={onSubmit} className="flex w-full max-w-sm flex-col gap-5" noValidate>
          <div className="flex items-center gap-3">
            <Logo className="size-11" />
            <div className="flex items-baseline gap-2">
              <span className="text-[22px] font-extrabold tracking-tight">KMarket</span>
              <span lang="ko" className="text-xs text-muted">케이마켓</span>
            </div>
          </div>

          <div>
            <h1 className="text-2xl font-extrabold tracking-tight">Connexion</h1>
            <p className="mt-1 text-sm text-muted">Accédez au tableau de bord de la plateforme.</p>
          </div>

          <label className="flex flex-col gap-1.5 text-sm font-semibold">
            Email
            <input
              type="email"
              autoComplete="username"
              placeholder="prenom@market.test"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={fieldCls}
            />
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-semibold">
            Mot de passe
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={fieldCls}
            />
          </label>

          {error && (
            <p role="alert" className="text-sm font-semibold text-bad">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={pending || !email || !password}
            className="min-h-11 cursor-pointer rounded-xl bg-accent px-3.5 font-semibold text-accent-ink transition-opacity disabled:cursor-not-allowed disabled:opacity-50"
          >
            {pending ? 'Connexion…' : 'Se connecter'}
          </button>

          <p className="text-center text-xs text-muted">Pas de compte ? Les comptes sont créés par un administrateur.</p>

          {DEMO_ACCOUNTS.length > 0 && (
            <div className="flex flex-col gap-2 border-t border-line pt-4 text-xs text-muted">
              Comptes de démo (dev) :
              <div className="flex flex-wrap gap-1.5">
                {DEMO_ACCOUNTS.map((a) => (
                  <button
                    key={a.email}
                    type="button"
                    onClick={() => setEmail(a.email)}
                    className="cursor-pointer rounded-lg bg-surface-2 px-2.5 py-1 font-semibold text-ink transition-opacity hover:opacity-80"
                  >
                    {a.label}
                  </button>
                ))}
              </div>
            </div>
          )}
        </form>
      </section>
    </main>
  );
}