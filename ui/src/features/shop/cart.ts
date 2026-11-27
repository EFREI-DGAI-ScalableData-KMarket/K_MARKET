import { useSyncExternalStore } from 'react';

/** Panier du visiteur : ids et quantités seulement ; prix et noms relus au catalogue à l'affichage. */
export type CartLine = { product_id: number; quantity: number };

const KEY = 'kmarket.cart';
export const MAX_QUANTITY = 10;

const isLine = (l: unknown): l is CartLine =>
  typeof l === 'object' && l !== null &&
  Number.isInteger((l as CartLine).product_id) && Number.isInteger((l as CartLine).quantity) && (l as CartLine).quantity > 0;

function load(): CartLine[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isLine) : [];
  } catch {
    return [];
  }
}

let lines = load();
const listeners = new Set<() => void>();

function save(next: CartLine[]) {
  lines = next;
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    /* stockage indisponible : le panier vit le temps de la page */
  }
  listeners.forEach((notify) => notify());
}

const clamp = (q: number) => Math.min(MAX_QUANTITY, Math.max(1, q));

export const cart = {
  add(productId: number, quantity = 1) {
    const current = lines.find((l) => l.product_id === productId);
    save(
      current
        ? lines.map((l) => (l.product_id === productId ? { ...l, quantity: clamp(l.quantity + quantity) } : l))
        : [...lines, { product_id: productId, quantity: clamp(quantity) }],
    );
  },
  setQuantity(productId: number, quantity: number) {
    save(lines.map((l) => (l.product_id === productId ? { ...l, quantity: clamp(quantity) } : l)));
  },
  remove(productId: number) {
    save(lines.filter((l) => l.product_id !== productId));
  },
  clear() {
    save([]);
  },
};

function subscribe(notify: () => void) {
  listeners.add(notify);
  return () => listeners.delete(notify);
}

export function useCart(): CartLine[] {
  return useSyncExternalStore(subscribe, () => lines);
}