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