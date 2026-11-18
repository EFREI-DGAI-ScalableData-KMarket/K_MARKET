import { z } from 'zod';
import { parseEurosToCents } from '../../lib/format';
import { ATTRIBUTE_FIELDS, type Category, type FieldDef } from './categories';

/** Valeurs brutes du formulaire (ce que l'utilisateur tape). */
export type FormValues = {
  name: string;
  category: Category;
  price: string;
  description: string;
  tags: string;
  attributes: Record<string, string | boolean>;
};

const required = 'Obligatoire';

const splitList = (v: string) => v.split(',').map((s) => s.trim()).filter(Boolean);

/** Un champ d'attribut → règle Zod. Saisie vide d'un champ facultatif = attribut absent. */
function fieldSchema(f: FieldDef): z.ZodType {
  switch (f.kind) {
    case 'bool':
      return z.boolean();
    case 'list':
      return z.string().transform((v) => {
        const list = splitList(v);
        return list.length ? list : undefined;
      });
    case 'int':
      return z.string().trim().transform((v, ctx) => {
        if (v === '') {
          if (f.required) ctx.addIssue({ code: 'custom', message: required });
          return f.required ? z.NEVER : undefined;
        }
        if (!/^\d+$/.test(v)) {
          ctx.addIssue({ code: 'custom', message: 'Nombre entier attendu' });
          return z.NEVER;
        }
        const n = Number(v);
        if (f.min !== undefined && n < f.min) {
          ctx.addIssue({ code: 'custom', message: `Minimum ${f.min}` });
          return z.NEVER;
        }
        return n;
      });
    case 'enum': {
      const values = (f.options ?? []).map((o) => o.value);
      return z.string().transform((v, ctx) => {
        if (v === '') {
          if (f.required) ctx.addIssue({ code: 'custom', message: required });
          return f.required ? z.NEVER : undefined;
        }
        if (!values.includes(v)) {
          ctx.addIssue({ code: 'custom', message: 'Valeur inconnue' });
          return z.NEVER;
        }
        return v;
      });
    }
    case 'text':
    case 'date':
      return z.string().trim().transform((v, ctx) => {
        if (v === '') {
          if (f.required) ctx.addIssue({ code: 'custom', message: required });
          return f.required ? z.NEVER : undefined;
        }
        if (f.kind === 'date' && !/^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.test(v)) {
          ctx.addIssue({ code: 'custom', message: 'Date au format AAAA-MM-JJ' });
          return z.NEVER;
        }
        if (f.pattern && !f.pattern.re.test(v)) {
          ctx.addIssue({ code: 'custom', message: f.pattern.message });
          return z.NEVER;
        }
        return v;
      });
  }
}

/** Schéma complet pour une catégorie : équivalent côté écran de la branche `oneOf` du validator. */
export function productSchema(category: Category) {
  const shape = Object.fromEntries(ATTRIBUTE_FIELDS[category].map((f) => [f.key, fieldSchema(f)]));
  return z.object({
    name: z.string().trim().min(1, required),
    price: z.string().transform((v, ctx) => {
      const cents = parseEurosToCents(v);
      if (cents === null) {
        ctx.addIssue({ code: 'custom', message: 'Montant en euros, ex. 24,90' });
        return z.NEVER;
      }
      return cents;
    }),
    description: z.string().trim().transform((v) => v || null),
    tags: z.string().transform(splitList),
    attributes: z.object(shape),
  });
}

export type ParsedProduct = {
  name: string;
  price_cents: number;
  description: string | null;
  tags: string[];
  attributes: Record<string, unknown>;
};

/** Valide le formulaire. Renvoie le produit prêt à envoyer, ou les erreurs par champ ("attributes.isbn"). */
export function parseProductForm(values: FormValues):
  | { ok: true; product: ParsedProduct }
  | { ok: false; errors: Record<string, string> } {
  const result = productSchema(values.category).safeParse(values);
  if (result.success) {
    const { price, ...rest } = result.data;
    // JSON.stringify retire les attributs `undefined` (champs facultatifs laissés vides).
    return { ok: true, product: { ...rest, price_cents: price, attributes: result.data.attributes as Record<string, unknown> } };
  }
  const errors: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = issue.path.join('.');
    errors[key] ??= issue.message;
  }
  return { ok: false, errors };
}

/** Attributs vides pour une catégorie (au changement de catégorie dans le formulaire). */
export function emptyAttributes(category: Category): Record<string, string | boolean> {
  return Object.fromEntries(ATTRIBUTE_FIELDS[category].map((f) => [f.key, f.kind === 'bool' ? false : '']));
}
