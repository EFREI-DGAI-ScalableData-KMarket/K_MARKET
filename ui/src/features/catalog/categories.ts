/**
 * Catégories (D1) et attributs propres à chacune.
 * Reprend le validator $jsonSchema de db/mongo/01_catalog.js et les types du schéma GraphQL :
 * `required: true` = champ obligatoire du validator (non nul en GraphQL).
 * C'est la seule définition côté front : le formulaire ET sa validation (schema.ts) en découlent.
 */
export const CATEGORIES = ['Album', 'Lightstick', 'Merch', 'Drama', 'Livre', 'Beaute'] as const;
export type Category = (typeof CATEGORIES)[number];

/** Les codes n'ont ni accent ni tiret (enum GraphQL) ; l'écran affiche les libellés. */
export const CATEGORY_LABELS: Record<Category, string> = {
  Album: 'Album',
  Lightstick: 'Lightstick',
  Merch: 'Merch',
  Drama: 'Drama',
  Livre: 'Livre',
  Beaute: 'Beauté',
};

export type Option = { value: string; label: string };

export type FieldDef = {
  key: string;
  label: string;
  kind: 'text' | 'int' | 'bool' | 'enum' | 'list' | 'date';
  required?: boolean;
  /** int : valeur minimale */
  min?: number;
  /** enum : valeurs stockées en base et leurs libellés */
  options?: Option[];
  /** text : format imposé par le validator */
  pattern?: { re: RegExp; message: string };
  hint?: string;
};

export const ATTRIBUTE_FIELDS: Record<Category, FieldDef[]> = {
  Album: [
    { key: 'artist', label: 'Artiste', kind: 'text', required: true },
    { key: 'version', label: 'Version', kind: 'text', required: true, hint: 'ex. ver. A' },
    {
      key: 'type', label: "Type d'album", kind: 'enum', required: true,
      options: [
        { value: 'studio', label: 'Studio' },
        { value: 'mini', label: 'Mini-album' },
        { value: 'single', label: 'Single' },
        { value: 'mixtape', label: 'Mixtape' },
        { value: 'anthology', label: 'Anthologie' },
      ],
    },
    { key: 'release_date', label: 'Date de sortie', kind: 'date', required: true },
    { key: 'photocards', label: 'Photocards', kind: 'int', min: 0 },
    { key: 'limited_edition', label: 'Édition limitée', kind: 'bool' },
  ],
  Lightstick: [
    { key: 'artist', label: 'Artiste', kind: 'text', required: true },
    { key: 'version', label: 'Version', kind: 'text', required: true },
    { key: 'bluetooth', label: 'Bluetooth', kind: 'bool' },
    { key: 'piles', label: 'Piles', kind: 'text', hint: 'ex. 3 × AAA' },
  ],
  Merch: [
    { key: 'artist', label: 'Artiste', kind: 'text', required: true },
    { key: 'type', label: 'Type', kind: 'text', required: true, hint: 'ex. T-shirt, poster, tote bag' },
    { key: 'size', label: 'Tailles', kind: 'list', hint: 'séparées par des virgules' },
    { key: 'couleur', label: 'Couleurs', kind: 'list', hint: 'séparées par des virgules' },
    { key: 'matiere', label: 'Matière', kind: 'text' },
    { key: 'format', label: 'Format', kind: 'text' },
    { key: 'pieces', label: 'Pièces', kind: 'int', min: 1 },
  ],
  Drama: [
    { key: 'serie', label: 'Série', kind: 'text', required: true },
    {
      key: 'support', label: 'Support', kind: 'enum', required: true,
      options: [
        { value: 'DVD', label: 'DVD' },
        { value: 'Blu_ray', label: 'Blu-ray' },
        { value: 'Coffret', label: 'Coffret' },
        { value: 'Artbook', label: 'Artbook' },
      ],
    },
    { key: 'saison', label: 'Saison', kind: 'int', min: 1 },
    { key: 'episodes', label: 'Épisodes', kind: 'int', min: 1 },
    { key: 'sous_titres', label: 'Sous-titres', kind: 'list', hint: 'langues séparées par des virgules' },
  ],
  Livre: [
    {
      key: 'isbn', label: 'ISBN', kind: 'text', required: true,
      pattern: { re: /^97[89][0-9]{10}$/, message: '13 chiffres, commençant par 978 ou 979' },
      hint: '13 chiffres sans tiret',
    },
    {
      key: 'langue', label: 'Langue', kind: 'enum', required: true,
      options: [
        { value: 'fr', label: 'Français' },
        { value: 'en', label: 'Anglais' },
        { value: 'ko', label: 'Coréen' },
      ],
    },
    { key: 'auteur', label: 'Auteur', kind: 'text' },
    { key: 'pages', label: 'Pages', kind: 'int', min: 1 },
    { key: 'genre', label: 'Genre', kind: 'text' },
  ],
  Beaute: [
    { key: 'marque', label: 'Marque', kind: 'text', required: true },
    { key: 'contenance_ml', label: 'Contenance (ml)', kind: 'int', required: true, min: 1 },
    { key: 'type', label: 'Type', kind: 'text', hint: 'ex. sérum, crème' },
    { key: 'type_peau', label: 'Type de peau', kind: 'text' },
    { key: 'ingredients', label: 'Ingrédients', kind: 'list', hint: 'séparés par des virgules' },
  ],
};