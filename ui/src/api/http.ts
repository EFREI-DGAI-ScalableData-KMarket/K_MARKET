import { getToken } from '../auth/auth';


export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const DEFAULT_MESSAGES: Record<number, string> = {
  401: 'Session absente ou expirée : reconnectez-vous.',
  403: "Votre rôle ne permet pas cette action.",
  404: 'Introuvable.',
  429: 'Trop de requêtes : réessayez dans un instant.',
};


export async function http<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  const res = await fetch(path, { ...init, headers });
  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T;
  }


  let code = `HTTP_${res.status}`;
  let message = DEFAULT_MESSAGES[res.status] ?? `Erreur ${res.status}`;
  let details: unknown;
  try {
    const body = await res.json();
    if (body?.code) code = body.code;
    if (body?.message && res.status !== 403 && res.status !== 401) message = body.message;
    details = body?.details;
  } catch {
    /* corps non JSON */
  }
  throw new ApiError(res.status, code, message, details);
}
