import { ApiError, http } from './http';

type GraphQLError = { message: string; extensions?: { code?: string; details?: unknown } };


const STATUS_BY_CODE: Record<string, number> = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  VALIDATION_ERROR: 400,
  CONFLICT: 409,
  UPSTREAM_ERROR: 502,
};


export async function gql<T>(endpoint: string, query: string, variables?: Record<string, unknown>): Promise<T> {
  const body = await http<{ data?: T; errors?: GraphQLError[] }>(endpoint, {
    method: 'POST',
    body: JSON.stringify({ query, variables }),
  });
  const err = body.errors?.[0];
  if (err) {
    const code = err.extensions?.code ?? 'GRAPHQL_ERROR';
    const status = STATUS_BY_CODE[code] ?? 500;
    const message = status === 403 ? "Votre rôle ne permet pas cette action." : err.message;
    throw new ApiError(status, code, message, err.extensions?.details);
  }
  return body.data as T;
}
