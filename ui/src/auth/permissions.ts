import type { Role } from './auth';

/**
 * Matrice des droits côté écran (D3), reprise des contrats des services.
 * Sert uniquement à ne pas proposer une action vouée au 403 ; le refus réel vient de l'API.
 */
const RULES = {
  'catalog.write': ['admin', 'catalog_manager'],
  'stock.update': ['admin', 'logistics'],
  'warehouses.read': ['admin', 'logistics', 'analyst'], // remplissage ; les noms seuls sont ouverts à tous via /warehouses/names
  'shipments.read': ['admin', 'logistics', 'analyst'],
  'shipments.update': ['admin', 'logistics'],
} satisfies Record<string, Role[]>;

export type Permission = keyof typeof RULES;

export function can(roles: Role[], permission: Permission): boolean {
  return roles.some((r) => (RULES[permission] as Role[]).includes(r));
}