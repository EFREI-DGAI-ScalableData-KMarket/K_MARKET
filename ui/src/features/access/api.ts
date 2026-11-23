import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../../api/http';

/** Contrat d'auth-service (admin seulement) : GET /admin/users, /admin/roles, /admin/api-keys ; POST /admin/users. */
export type Account = { id: number; email: string; is_active: boolean; roles: string[]; created_at: string | null };
export type RoleInfo = { id: number; name: string; description: string | null };
export type ApiKeyInfo = { id: number; name: string; role_id: number; is_active: boolean; created_at: string | null };
export type NewAccount = { email: string; password: string; role_ids: number[] };

export const useAccounts = () => useQuery({ queryKey: ['admin', 'users'], queryFn: () => http<Account[]>('/v1/admin/users') });

export const useRoles = () =>
  useQuery({ queryKey: ['admin', 'roles'], queryFn: () => http<RoleInfo[]>('/v1/admin/roles'), staleTime: Infinity });

export const useApiKeys = () => useQuery({ queryKey: ['admin', 'api-keys'], queryFn: () => http<ApiKeyInfo[]>('/v1/admin/api-keys') });

export function useCreateAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NewAccount) => http<Account>('/v1/admin/users', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  });
}

/**
 * DELETE /admin/api-keys/{id} : le service ne peut plus échanger cette clé contre un jeton.
 * Les jetons déjà émis restent valides jusqu'à leur expiration (15 min) : le JWT n'est pas révocable.
 */
export function useRevokeApiKey() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => http<void>(`/v1/admin/api-keys/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'api-keys'] }),
  });
}