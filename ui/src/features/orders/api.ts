import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../../api/http';

/** Statuts du contrat Commandes (OrderStatus), dans l'ordre du cycle de vie. */
export const ORDER_STATUSES = [
  'pending',
  'stock_reserved',
  'paid',
  'preparing',
  'shipped',
  'delivered',
  'payment_failed',
  'rejected',
  'cancelled',
] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

/** Schéma Order du contrat (GET /orders, PATCH /orders/{id}/status). */
export type Order = {
  id: number;
  customer_id: number;
  status: OrderStatus;
  total_cents: number;
  created_at: string;
  updated_at: string;
  /** Pas encore renvoyé par GET /orders (jointure orders.customers demandée) : affiché dès qu'il arrive. */
  customer?: { id: number; name: string };
};

/** Schéma OrderDetail (GET /orders/{id}) : product_name est figé à la commande (lu du Catalogue). */
export type OrderDetail = Omit<Order, 'customer'> & {
  customer: { id: number; name: string; email: string } | null;
  items: { product_id: number; product_name: string; quantity: number; unit_price_cents: number; line_total_cents: number }[];
};

/** offset = page × taille : toujours un multiple de limit (le service calcule la page par offset / limit). */
export const ORDERS_PAGE_SIZE = 20;

export function useOrders(filter: { status: OrderStatus | null; customerId: number | null }, page: number) {
  const params = new URLSearchParams({ limit: String(ORDERS_PAGE_SIZE), offset: String(page * ORDERS_PAGE_SIZE) });
  if (filter.status) params.set('status', filter.status);
  if (filter.customerId !== null) params.set('customer_id', String(filter.customerId));
  return useQuery({
    queryKey: ['orders', 'list', filter, page],
    queryFn: () => http<{ items: Order[]; total: number }>(`/v1/orders?${params}`),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });
}

/** Nombre de commandes d'un statut (null = toutes) : seul `total` est lu (limit 1). */
export function useOrderCount(status: OrderStatus | null) {
  const params = new URLSearchParams({ limit: '1' });
  if (status) params.set('status', status);
  return useQuery({
    queryKey: ['orders', 'count', status],
    queryFn: () => http<{ total: number }>(`/v1/orders?${params}`).then((d) => d.total),
    refetchInterval: 30_000,
  });
}

export function useOrder(id: number) {
  return useQuery({
    queryKey: ['orders', 'detail', id],
    queryFn: () => http<OrderDetail>(`/v1/orders/${id}`),
    retry: false, // 404 = commande inconnue, inutile de réessayer
  });
}

/** Change le statut (409 si la transition est interdite) ; émet order_status_changed via l'outbox. */
export function useUpdateOrderStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: OrderStatus }) =>
      http<Order>(`/v1/orders/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['orders'] }), // liste, compteurs et détail
  });
}