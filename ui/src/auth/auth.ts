/**
 * Session utilisateur (auth-api).
 *
 * - Access token (JWT, 15 min) : en mémoire seulement.
 * - Refresh token (usage unique, 30 j) : en sessionStorage, pour que la session survive
 *   au rechargement de la page (pas à la fermeture de l'onglet).
 *   Limite assumée (ADR du front) : un script injecté (XSS) pourrait le lire. Un cookie
 *   httpOnly supprimerait ce risque mais demande une modification d'auth-api.
 *
 * Le refresh est partagé : si plusieurs appels reçoivent un 401 en même temps, un seul
 * POST /auth/refresh part et tous l'attendent (le refresh token ne sert qu'une fois).
 */
export type Role = 'admin' | 'catalog_manager' | 'analyst' | 'logistics' | 'service_account';

type TokenPair = { access_token: string; refresh_token: string | null; expires_in: number };

const REFRESH_KEY = 'kmarket.refresh_token';

let token: string | null = null;
let refreshing: Promise<boolean> | null = null;
let redirecting = false;

export function getToken(): string | null {
  return token;
}

function storeTokens(pair: TokenPair): void {
  token = pair.access_token;
  if (pair.refresh_token) sessionStorage.setItem(REFRESH_KEY, pair.refresh_token);
}

function clearSession(): void {
  token = null;
  sessionStorage.removeItem(REFRESH_KEY);
}

/** POST /auth/login. Lève une Error dont le message est prêt à afficher. */
export async function login(email: string, password: string): Promise<void> {
  let res: Response;
  try {
    res = await fetch('/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new Error("Service d'authentification injoignable.");
  }
  if (!res.ok) {
    const message =
      res.status === 401
        ? 'Email ou mot de passe incorrect.'
        : res.status === 400
          ? 'Email ou mot de passe invalide.'
          : res.status === 429
            ? 'Trop de tentatives : réessayez dans un instant.'
            : `Connexion impossible (erreur ${res.status}).`;
    throw new Error(message);
  }
  storeTokens(await res.json());
}

/** Obtient un nouvel access token. Un seul appel réseau, même si plusieurs requêtes le demandent. */
export function refresh(): Promise<boolean> {
  refreshing ??= doRefresh().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

async function doRefresh(): Promise<boolean> {
  const refreshToken = sessionStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return false;
  try {
    const res = await fetch('/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
    if (res.status === 401) {
      clearSession(); // refresh token expiré, révoqué ou déjà utilisé
      return false;
    }
    if (!res.ok) return false; // 429, 5xx : on garde le refresh token pour un prochain essai
    storeTokens(await res.json());
    return true;
  } catch {
    return false; // réseau
  }
}

/** Vrai si un access token valide est disponible (en le renouvelant si besoin). Utilisé par le routeur. */
export async function ensureSession(): Promise<boolean> {
  const exp = claims().exp;
  if (token && typeof exp === 'number' && exp * 1000 > Date.now() + 10_000) return true;
  return refresh();
}

/** Révoque les refresh tokens côté serveur (au mieux) puis efface la session locale. */
export async function logout(): Promise<void> {
  if (await ensureSession()) {
    try {
      await fetch('/v1/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
    } catch {
      /* la session locale est effacée quoi qu'il arrive */
    }
  }
  clearSession();
}

/** Session perdue en cours d'utilisation : retour à la page de connexion, puis à la page courante. */
export function sessionExpired(): void {
  clearSession();
  if (redirecting || location.pathname === '/login') return;
  redirecting = true;
  location.assign(`/login?next=${encodeURIComponent(location.pathname + location.search)}`);
}

/** Claims du JWT, décodés sans vérification (affichage seulement : c'est l'API qui décide, 401/403). */
function claims(): { sub?: unknown; email?: unknown; roles?: unknown; exp?: unknown } {
  if (!token) return {};
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload));
  } catch {
    return {};
  }
}

/** Rôles du claim `roles`, pour masquer une action vouée au 403. */
export function getRoles(): Role[] {
  const roles = claims().roles;
  return Array.isArray(roles) ? (roles as Role[]) : [];
}

/** Identifiant de l'utilisateur (claim `sub`). */
export function getSubject(): string | null {
  const sub = claims().sub;
  return typeof sub === 'string' ? sub : null;
}

/** Email de l'utilisateur (claim `email`, absent pour un compte technique). */
export function getEmail(): string | null {
  const email = claims().email;
  return typeof email === 'string' ? email : null;
}