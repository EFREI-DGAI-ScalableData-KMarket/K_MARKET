import type { Tone } from '../../ui/ui';
import type { OrderStatus } from './api';

/** Une couleur par étape : à faire (plein), en cours, en route, terminé, échec. */
export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: Tone }> = {
  pending: { label: 'En attente', tone: 'neutral' },
  stock_reserved: { label: 'Stock réservé', tone: 'neutral' },
  paid: { label: 'Payée', tone: 'accent' },
  preparing: { label: 'En préparation', tone: 'warn' },
  shipped: { label: 'Expédiée', tone: 'info' },
  delivered: { label: 'Livrée', tone: 'ok' },
  payment_failed: { label: 'Paiement refusé', tone: 'bad' },
  cancelled: { label: 'Annulée', tone: 'neutral' },
  rejected: { label: 'Refusée (stock)', tone: 'bad' }
};

/** Pastille de couleur (compteurs) assortie au ton du badge. */
export const TONE_DOT: Record<Tone, string> = {
  ok: 'bg-ok',
  warn: 'bg-warn',
  bad: 'bg-bad',
  info: 'bg-info',
  neutral: 'bg-muted',
  accent: 'bg-accent',
};

/**
 * Transitions autorisées, reprises de OrderStatus.java (orders-service) : le service renvoie 409 pour les autres.
 * Miroir pour l'affichage seulement ; si le service change, mettre à jour ici.
 */
const ALLOWED: Record<OrderStatus, readonly OrderStatus[]> = {
  pending: ['stock_reserved', 'payment_failed', 'cancelled'],
  stock_reserved: ['paid', 'payment_failed', 'cancelled'],
  paid: ['preparing', 'cancelled'],
  preparing: ['shipped'],
  shipped: ['delivered'],
  delivered: [],
  payment_failed: [],
  rejected: [],
  cancelled: [],
};

export function canTransition(from: OrderStatus, to: OrderStatus): boolean {
  return ALLOWED[from]?.includes(to) ?? false;
}

/** Étapes après le paiement, pour la frise du détail. */
export const STEPS = [
  { status: 'paid', label: 'Payée' },
  { status: 'preparing', label: 'Préparation' },
  { status: 'shipped', label: 'Expédiée' },
  { status: 'delivered', label: 'Livrée' },
] as const;

/** Rang dans la frise : -1 avant le paiement ; null pour une fin sans suite (refus, annulation). */
export function stepIndex(status: OrderStatus): number | null {
  if (status === 'payment_failed' || status === 'rejected' || status === 'cancelled') return null;
  return STEPS.findIndex((s) => s.status === status);
}