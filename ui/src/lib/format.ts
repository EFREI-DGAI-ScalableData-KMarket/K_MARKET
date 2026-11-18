const EUR = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' });


export const formatCents = (cents: number) => EUR.format(cents / 100);

export function parseEurosToCents(input: string): number | null {
  const s = input.trim().replace(/\s/g, '').replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  return Math.round(Number(s) * 100);
}

export const centsToInput = (cents: number) => (cents / 100).toFixed(2).replace('.', ',');
