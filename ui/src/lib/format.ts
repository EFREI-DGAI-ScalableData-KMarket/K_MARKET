const EUR = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' });


export const formatCents = (cents: number) => EUR.format(cents / 100);

export function parseEurosToCents(input: string): number | null {
  const s = input.trim().replace(/\s/g, '').replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  return Math.round(Number(s) * 100);
}

export const centsToInput = (cents: number) => (cents / 100).toFixed(2).replace('.', ',');
const DATE_TIME = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
const RELATIVE = new Intl.RelativeTimeFormat('fr-FR', { numeric: 'auto' });

export const formatDateTime = (iso: string) => DATE_TIME.format(new Date(iso));

/** « il y a 12 min », « il y a 3 h » pendant 24 h, puis la date complète. */
export function formatRelative(iso: string, now = Date.now()): string {
  const seconds = (new Date(iso).getTime() - now) / 1000; // négatif = passé
  const abs = Math.abs(seconds);
  if (abs < 60) return 'à l’instant';
  if (abs < 3600) return RELATIVE.format(Math.round(seconds / 60), 'minute');
  if (abs < 86_400) return RELATIVE.format(Math.round(seconds / 3600), 'hour');
  return formatDateTime(iso);
}