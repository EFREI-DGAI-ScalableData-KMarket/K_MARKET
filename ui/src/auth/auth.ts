/**
 * Jeton d'accès. Gardé en mémoire (pas dans localStorage, lisible par tout script de la page).
 *
 * En attendant auth-api : VITE_DEV_TOKEN (.env.local) sert de jeton de test.
 * Quand auth-api sera prêt, la page de login appellera setToken() avec le JWT reçu ;
 * le reste de l'app ne change pas.
 */
export type Role = 'admin' | 'catalog_manager' | 'analyst' | 'logistics' | 'service_account';

let token: string | null = import.meta.env.VITE_DEV_TOKEN || null;

export function getToken(): string | null {
  return token;
}

export function setToken(value: string | null): void {
  token = value;
}

/** Claims du JWT, décodés sans vérification (affichage seulement : c'est l'API qui décide, 401/403). */
function claims(): { sub?: string; roles?: unknown } {
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

/** Identifiant de l'utilisateur (claim `sub`), pour l'affichage. */
export function getSubject(): string | null {
  const sub = claims().sub;
  return typeof sub === 'string' ? sub : null;
}