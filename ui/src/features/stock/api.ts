import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, http } from '../../api/http';

/**
 * Format supposé identique à GET /stock/{product_id} (StockSummary), en tableau.
 * À vérifier contre inventory.openapi.yaml : c'est le seul endroit à adapter si le format diffère.
 */
export type StockSummary = { product_id: number; total_quantity: number };

export type WarehouseName = { id: number; name: string };

/** Ligne de stock d'un produit dans un entrepôt (StockItem du contrat). */
export type StockItem = { product_id: number; warehouse_id: number; warehouse_name: string; quantity: number; updated_at: string };
export type StockDetail = StockSummary & { by_warehouse: StockItem[] };

/**
 * Stock de plusieurs produits en une requête (GET /stock?product_ids=…, 200 ids max).
 * Un produit absent de la réponse n'a pas de ligne de stock : il vaut null dans la Map.
 */
export function useStockTotals(productIds: number[]) {
  const ids = [...new Set(productIds)].sort((a, b) => a - b);
  const query = useQuery({
    queryKey: ['stock', 'batch', ids],
    enabled: ids.length > 0,
    queryFn: () => http<StockSummary[]>(`/v1/stock?product_ids=${ids.join(',')}`),
    placeholderData: keepPreviousData,
  });
  const totals = new Map<number, number | null>();
  if (query.data) {
    for (const id of ids) totals.set(id, null);
    for (const s of query.data) totals.set(s.product_id, s.total_quantity);
  }
  return { totals, isPending: ids.length > 0 && query.isPending, error: query.error };
}

/** Produits dont le stock total est ≤ seuil, du plus bas au plus haut (statut catalogue inconnu du service Stock). */
export function useLowStock(threshold: number, limit: number) {
  return useQuery({
    queryKey: ['stock', 'low', threshold, limit],
    queryFn: () => http<StockSummary[]>(`/v1/stock/low?threshold=${threshold}&limit=${limit}`),
    refetchInterval: 30_000,
  });
}

/** Identifiant et nom des entrepôts (ouvert à catalog_manager pour le formulaire produit). */
export function useWarehouseNames(enabled: boolean) {
  return useQuery({
    queryKey: ['warehouses', 'names'],
    enabled,
    queryFn: () => http<WarehouseName[]>('/v1/warehouses/names'),
    staleTime: 5 * 60_000,
  });
}

/** Stock d'un produit, total et détail par entrepôt (GET /stock/{id}) ; null s'il n'a pas de ligne de stock. */
export function useProductStock(productId: number | null) {
  return useQuery({
    queryKey: ['stock', 'detail', productId],
    enabled: productId !== null,
    queryFn: async () => {
      try {
        return await http<StockDetail>(`/v1/stock/${productId}`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
}

/**
 * Fixe la quantité disponible d'un produit dans un entrepôt (PUT, valeur absolue, pas un ajout).
 * Rôles : admin, logistics. Le service émet l'événement Kafka inventory.stock-changed (reason « adjustment »).
 */
export function useUpdateStock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ productId, warehouseId, quantity }: { productId: number; warehouseId: number; quantity: number }) =>
      http<StockItem>(`/v1/stock/${productId}/warehouses/${warehouseId}`, {
        method: 'PUT',
        body: JSON.stringify({ quantity }),
      }),
    onSuccess: () => {
      // Le stock change partout : cartes, fiche, stocks bas, taux de rupture, remplissage des entrepôts.
      qc.invalidateQueries({ queryKey: ['stock'] });
      qc.invalidateQueries({ queryKey: ['logistics'] });
    },
  });
}