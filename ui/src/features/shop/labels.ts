import { CATEGORIES as CODES, CATEGORY_LABELS, type Category } from '../catalog/categories';

export const CATEGORIES = CODES.map((value) => ({ value, label: CATEGORY_LABELS[value] }));
export const categoryLabel = (c: Category) => CATEGORY_LABELS[c];

const LONG_DATE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' });

/** Valeur d'attribut lisible : booléen, liste, date ; null si vide. */
export function attributeValue(v: unknown, kind?: string): string | null {
  if (v === null || v === undefined || v === '') return null;
  if (typeof v === 'boolean') return v ? 'Oui' : 'Non';
  if (Array.isArray(v)) return v.length ? v.join(', ') : null;
  if (kind === 'date') {
    const d = new Date(String(v));
    if (!Number.isNaN(d.getTime())) return LONG_DATE.format(d);
  }
  return String(v);
}