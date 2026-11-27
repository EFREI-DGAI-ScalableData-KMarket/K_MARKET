import type { Role } from './auth';

/**
 * Matrice des droits côté écran (D3, D9, D10), reprise des contrats des services.
 * Sert à griser ce qu'un rôle ne peut pas faire et à afficher la matrice dans « Accès & rôles » :
 * une seule source pour les deux. Le refus réel vient toujours de l'API (401 / 403).
 */

/** Rôles de l'équipe : les seuls admis dans le back-office (customer et service_account n'y entrent pas). */
export const INTERNAL_ROLES = ['admin', 'catalog_manager', 'analyst', 'logistics'] as const satisfies readonly Role[];

export const ROLE_LABELS: Record<Role, string> = {
  admin: 'Administration',
  catalog_manager: 'Catalogue',
  analyst: 'Analyse',
  logistics: 'Logistique',
  customer: 'Client',
  service_account: 'Compte technique',
};

/** Onglets du back-office (matrice validée le 19/11, identique à la maquette du cours). */
export const TAB_ACCESS = {
  overview: ['admin', 'catalog_manager', 'analyst', 'logistics'],
  orders: ['admin', 'analyst', 'logistics'],
  catalog: ['admin', 'catalog_manager', 'analyst', 'logistics'],
  logistics: ['admin', 'analyst', 'logistics'],
  observability: ['admin', 'analyst'],
  access: ['admin'],
} satisfies Record<string, Role[]>;

export type Tab = keyof typeof TAB_ACCESS;

export const TAB_LABELS: Record<Tab, string> = {
  overview: "Vue d'ensemble",
  orders: 'Commandes',
  catalog: 'Catalogue',
  logistics: 'Logistique',
  observability: 'Observabilité',
  access: 'Accès & rôles',
};

/** Actions à l'intérieur des onglets. */
const RULES = {
  'catalog.write': ['admin', 'catalog_manager'],
  'stock.update': ['admin', 'logistics'],
  'warehouses.read': ['admin', 'logistics', 'analyst'], // remplissage ; les noms seuls sont ouverts à tous via /warehouses/names
  'shipments.read': ['admin', 'logistics', 'analyst'],
  'shipments.update': ['admin', 'logistics'],
  'users.manage': ['admin'],
  'orders.prepare': ['admin', 'logistics'],
  'orders.cancel': ['admin'],
} satisfies Record<string, Role[]>;

export type Permission = keyof typeof RULES;

export const PERMISSION_LABELS: Record<Permission, string> = {
  'catalog.write': 'Créer, modifier, archiver un produit',
  'stock.update': 'Ajuster le stock',
  'warehouses.read': 'Voir le remplissage des entrepôts',
  'shipments.read': 'Voir les expéditions',
  'shipments.update': 'Faire avancer une expédition',
  'users.manage': 'Gérer les comptes et les rôles',
  'orders.prepare': 'Passer une commande en préparation',
  'orders.cancel': 'Annuler une commande',
};

const allowedRoles = (key: Tab | Permission): readonly Role[] =>
  key in TAB_ACCESS ? TAB_ACCESS[key as Tab] : RULES[key as Permission];

export function can(roles: Role[], permission: Permission): boolean {
  return roles.some((r) => (RULES[permission] as Role[]).includes(r));
}

export function canTab(roles: Role[], tab: Tab): boolean {
  return roles.some((r) => (TAB_ACCESS[tab] as Role[]).includes(r));
}

/** Vrai si le compte a au moins un rôle de l'équipe (accès au back-office). */
export function isInternal(roles: Role[]): boolean {
  return roles.some((r) => (INTERNAL_ROLES as readonly Role[]).includes(r));
}

/** Rôles qui ont le droit, pour le survol d'un élément grisé : « Réservé à : admin, logistics ». */
export function reservedTo(key: Tab | Permission): string {
  return `Réservé à : ${allowedRoles(key).join(', ')}`;
}

/** Pour la matrice : le rôle a-t-il ce droit ? */
export function roleHas(role: Role, key: Tab | Permission): boolean {
  return allowedRoles(key).includes(role);
}

export const PERMISSIONS = Object.keys(RULES) as Permission[];
export const TABS = Object.keys(TAB_ACCESS) as Tab[];