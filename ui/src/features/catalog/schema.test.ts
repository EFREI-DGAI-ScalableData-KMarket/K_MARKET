import { describe, expect, it } from 'vitest';
import { emptyAttributes, parseProductForm, type FormValues } from './schema';

const base = (patch: Partial<FormValues>): FormValues => ({
  name: 'Album test',
  category: 'Album',
  price: '24,90',
  description: '',
  tags: '',
  attributes: emptyAttributes('Album'),
  ...patch,
});

describe('parseProductForm (miroir du validator MongoDB)', () => {
  it('accepte un album complet et convertit les types', () => {
    const r = parseProductForm(
      base({
        tags: 'kpop, ver. A',
        attributes: { ...emptyAttributes('Album'), artist: 'X', version: 'A', type: 'mini', release_date: '2026-11-01', photocards: '2', limited_edition: true },
      }),
    );
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.product.price_cents).toBe(2490);
    expect(r.product.tags).toEqual(['kpop', 'ver. A']);
    expect(r.product.description).toBeNull();
    expect(r.product.attributes).toMatchObject({ artist: 'X', type: 'mini', photocards: 2, limited_edition: true });
  });

  it('signale les attributs obligatoires manquants', () => {
    const r = parseProductForm(base({}));
    expect(r.ok).toBe(false);
    if (r.ok) return;
    expect(Object.keys(r.errors)).toEqual(
      expect.arrayContaining(['attributes.artist', 'attributes.version', 'attributes.type', 'attributes.release_date']),
    );
  });

  it("refuse un ISBN hors du motif ^97[89][0-9]{10}$", () => {
    const r = parseProductForm(
      base({ category: 'Livre', attributes: { ...emptyAttributes('Livre'), isbn: '9771234567890', langue: 'fr' } }),
    );
    expect(r.ok).toBe(false);
    if (r.ok) return;
    expect(r.errors['attributes.isbn']).toBeDefined();
  });

  it('omet un champ facultatif vide et refuse un entier sous le minimum', () => {
    const ok = parseProductForm(
      base({ category: 'Beaute', attributes: { ...emptyAttributes('Beaute'), marque: 'M', contenance_ml: '30' } }),
    );
    expect(ok.ok && ok.product.attributes.type_peau).toBeUndefined();
    const bad = parseProductForm(
      base({ category: 'Beaute', attributes: { ...emptyAttributes('Beaute'), marque: 'M', contenance_ml: '0' } }),
    );
    expect(!bad.ok && bad.errors['attributes.contenance_ml']).toBe('Minimum 1');
  });

  it('refuse un prix invalide', () => {
    const r = parseProductForm(base({ price: '12,999' }));
    expect(!r.ok && r.errors.price).toBeTruthy();
  });

  it('applique la règle de date du validator (mois 01-12, jour 01-31)', () => {
    const album = (release_date: string) =>
      parseProductForm(base({ attributes: { ...emptyAttributes('Album'), artist: 'X', version: 'A', type: 'mini', release_date } }));
    expect(album('2026-11-16').ok).toBe(true);
    expect(album('2026-13-01').ok).toBe(false);
    expect(album('2026-11-32').ok).toBe(false);
    expect(album('16/11/2026').ok).toBe(false);
  });
});
