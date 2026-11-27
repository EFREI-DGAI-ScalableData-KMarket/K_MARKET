import { useQuery } from '@tanstack/react-query';
import { http } from '../../api/http';

/** Contrat : contracts/dashboard.openapi.yaml (GET /v1/dashboard/summary, service dashboard). */
export type DashboardSummary = {
  data_as_of: string | null;
  today: {
    orders: number;
    orders_paid: number;
    revenue_cents: number;
    average_basket_cents: number | null;
    payments_failed: number;
  };
  hourly: { hour: string; orders: number; revenue_cents: number }[];
  stock_alerts: {
    product_id: number;
    remaining_units: number;
    units_per_minute: number;
    minutes_left: number;
    raised_at: string;
  }[];
  warehouses: {
    warehouse_id: number;
    used_units: number;
    capacity_units: number;
    fill_rate: number;
    near_full: boolean;
    near_full_since: string | null;
    measured_at: string;
  }[];
};

/** Le job écrit à chaque lot (quelques secondes) : sans écriture depuis 2 min, le flux est considéré comme arrêté. */
export const STALE_AFTER_MS = 2 * 60_000;

export function useDashboardSummary() {
  return useQuery({
    queryKey: ['dashboard', 'summary'],
    queryFn: () => http<DashboardSummary>('/v1/dashboard/summary'),
    refetchInterval: 10_000,
  });
}