/**
 * Niveaux de stock, partagés par le Catalogue et la Vue d'ensemble :
 * - rupture : 0 unité, on ne peut plus vendre ;
 * - stock bas : 1 à LOW_STOCK_THRESHOLD unités, la rupture approche.
 */
export const LOW_STOCK_THRESHOLD = 10;

export type StockLevel = 'none' | 'out' | 'low' | 'ok';

/** null = le produit n'a pas de ligne de stock. */
export function stockLevel(quantity: number | null): StockLevel {
  if (quantity === null) return 'none';
  if (quantity === 0) return 'out';
  if (quantity <= LOW_STOCK_THRESHOLD) return 'low';
  return 'ok';
}
