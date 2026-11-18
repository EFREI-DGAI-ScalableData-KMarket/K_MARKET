import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { login } from '../auth/auth';
import { useTheme } from '../app/theme';

/** Comptes créés par seed/seed.py (mot de passe : DEMO_PASSWORD). Absents du build de production. */
const DEMO_EMAILS = import.meta.env.DEV
  ? ['admin@market.test', 'catalog@market.test', 'analyst@market.test', 'logistics@market.test']
  : [];

const fieldCls =
  'min-h-11 w-full rounded-xl border border-line bg-surface px-3.5 text-ink outline-none focus:border-accent';

export function LoginPage() {
  useTheme(); // même thème que le reste de l'application
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      await login(email.trim(), password);
      // Retour à la page demandée ; uniquement un chemin interne (pas de redirection vers un autre site).
      const next = params.get('next');
      navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connexion impossible.');
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <form
        onSubmit={onSubmit}
        className="flex w-full max-w-sm flex-col gap-4 rounded-2xl border border-line bg-surface p-7"
        noValidate
      >
        <div className="flex items-baseline gap-2">
          <span className="text-[22px] font-extrabold tracking-tight">KMarket</span>
          <span lang="ko" className="text-xs text-muted">케이마켓</span>
        </div>
        <h1 className="text-lg font-bold">Connexion</h1>

        <label className="flex flex-col gap-1.5 text-sm font-semibold">
          Email
          <input
            type="email"
            autoComplete="username"
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

        {DEMO_EMAILS.length > 0 && (
          <div className="flex flex-col gap-1.5 border-t border-line pt-4 text-xs text-muted">
            Comptes de démo (dev) :
            <div className="flex flex-wrap gap-1.5">
              {DEMO_EMAILS.map((demo) => (
                <button
                  key={demo}
                  type="button"
                  onClick={() => setEmail(demo)}
                  className="cursor-pointer rounded-lg bg-surface-2 px-2 py-1 text-ink hover:opacity-80"
                >
                  {demo.split('@')[0]}
                </button>
              ))}
            </div>
          </div>
        )}
      </form>
    </main>
  );
}