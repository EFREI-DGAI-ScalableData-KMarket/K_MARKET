import { refresh, sessionExpired } from '../auth/auth';
import { ApiError, http } from './http';

type GraphQLError = { message: string; extensions?: { code?: string; details?: unknown } };
type GraphQLResponse<T> = { data?: T; errors?: GraphQLError[] };

/** Codes d'erreur GraphQL du catalogue → statut HTTP équivalent, pour traiter REST et GraphQL pareil. */
const STATUS_BY_CODE: Record<string, number> = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  VALIDATION_ERROR: 400,
  CONFLICT: 409,
  UPSTREAM_ERROR: 502,
};

const isUnauthorized = (body: GraphQLResponse<unknown>) => body.errors?.[0]?.extensions?.code === 'UNAUTHORIZED';

/**
 * Requête GraphQL. Le serveur répond HTTP 200 même en erreur : le code est dans
 * errors[].extensions.code. Les erreurs de la gateway (429…) arrivent, elles, en HTTP.
 * Un jeton expiré arrive donc en 200 + UNAUTHORIZED : http() ne le voit pas, on le traite ici.
 */
export async function gql<T>(endpoint: string, query: string, variables?: Record<string, unknown>): Promise<T> {
  const post = () =>
    http<GraphQLResponse<T>>(endpoint, {
      method: 'POST',
      body: JSON.stringify({ query, variables }),
    });

  let body = await post();
  if (isUnauthorized(body)) {
    if (await refresh()) body = await post();
    if (isUnauthorized(body)) sessionExpired();
  }

  const err = body.errors?.[0];
  if (err) {
    const code = err.extensions?.code ?? 'GRAPHQL_ERROR';
    const status = STATUS_BY_CODE[code] ?? 500;
    const message = status === 403 ? "Votre rôle ne permet pas cette action." : err.message;
    throw new ApiError(status, code, message, err.extensions?.details);
  }
  return body.data as T;
}