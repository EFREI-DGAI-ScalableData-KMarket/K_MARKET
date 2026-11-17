import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '../../api/http';

/** Entrepôt et remplissage (schéma Warehouse du contrat Stock). */
export type Warehouse = {
  id: number;
  name: string;
  capacity_units: number;
  used_units: number;
  /** used_units / capacity_units, de 0 à 1 */
  fill_rate: number;
  /** near_full si fill_rate > 0,90 */
  status: 'normal' | 'near_full';
};

export type ShipmentStatus = 'preparing' | 'shipped' | 'delivered';

export type Shipment = {
  id: number;
  order_id: number;
  warehouse_id: number;
  status: ShipmentStatus;
  created_at: string;
  shipped_at: string | null;
  delivered_at: string | null;
};

/**
 * Source du taux de remplissage.
 * PROVISOIRE : calcul du service Stock depuis PostgreSQL. Le sujet le veut calculé par Spark dans le Data Lake
 * (Kafka → raw → curated → consumption). Quand l'API du lake existera, avec le même format que Warehouse
 * (plus la date du calcul), il suffira de changer FILL_RATE_SOURCE.
 */
export const FILL_RATE_SOURCE = {
  url: '/v1/warehouses',
  label: 'service Stock (PostgreSQL), en attendant le calcul Spark du Data Lake',
  provisional: true,
};

export function useWarehouses() {
  return useQuery({
    queryKey: ['logistics', 'warehouses'],
    queryFn: () => http<Warehouse[]>(FILL_RATE_SOURCE.url),
    refetchInterval: 15_000,
  });
}

export const SHIPMENTS_PAGE_SIZE = 20;

export function useShipments(filter: { warehouseId: number | null; status: ShipmentStatus | null }, page: number) {
  const params = new URLSearchParams({ limit: String(SHIPMENTS_PAGE_SIZE), offset: String(page * SHIPMENTS_PAGE_SIZE) });
  if (filter.warehouseId !== null) params.set('warehouse_id', String(filter.warehouseId));
  if (filter.status) params.set('status', filter.status);
  return useQuery({
    queryKey: ['logistics', 'shipments', filter, page],
    queryFn: () => http<{ items: Shipment[]; total: number }>(`/v1/shipments?${params}`),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });
}

/** Étape suivante d'une expédition (pas de retour arrière : le service renvoie 409 sinon). */
export const NEXT_STATUS: Partial<Record<ShipmentStatus, ShipmentStatus>> = { preparing: 'shipped', shipped: 'delivered' };

/** Fait avancer une expédition (rôles admin, logistics) ; émet inventory.shipment-status-changed. */
export function useAdvanceShipment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: ShipmentStatus }) =>
      http<Shipment>(`/v1/shipments/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['logistics', 'shipments'] }),
  });
}