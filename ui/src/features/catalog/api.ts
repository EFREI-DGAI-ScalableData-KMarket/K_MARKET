import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { gql } from '../../api/graphql';
import { ATTRIBUTE_FIELDS, type Category } from './categories';
import { emptyAttributes, type FormValues, type ParsedProduct } from './schema';
import { centsToInput } from '../../lib/format';

const ENDPOINT = '/v1/catalog';

export type ProductStatus = 'active' | 'inactive';

export type ProductSummary = {
  product_id: number;
  name: string;
  category: Category;
  price_cents: number;
  status: ProductStatus;
  image_url: string;
};

export type Product = ProductSummary & {
  tags: string[];
  description: string | null;
  attributes: Record<string, unknown> & { __typename: string };
};

export type ProductFilter = {
  category?: Category | null;
  status?: ProductStatus | null;
  search?: string | null;
  min_price_cents?: number | null;
  max_price_cents?: number | null;
};

const SUMMARY = 'product_id name category price_cents status image_url';

/**
 * `type` existe dans Album (AlbumType!), Merch (String!) et Beaute (String) : GraphQL refuse ces
 * champs homonymes de types différents sans alias. On les renomme ici, et toForm() les remet en `type`.
 */
const FULL = `${SUMMARY} tags description
  attributes {
    __typename
    ... on AlbumAttributes { artist version album_type: type release_date photocards limited_edition }
    ... on LightstickAttributes { artist version bluetooth piles }
    ... on MerchAttributes { artist merch_type: type size couleur matiere format pieces }
    ... on DramaAttributes { serie support saison episodes sous_titres }
    ... on LivreAttributes { isbn langue auteur pages genre }
    ... on BeauteAttributes { marque contenance_ml beaute_type: type type_peau ingredients }
  }`;

const TYPE_ALIAS: Partial<Record<Category, string>> = { Album: 'album_type', Merch: 'merch_type', Beaute: 'beaute_type' };

export const PAGE_SIZE = 24;

export function useProducts(filter: ProductFilter, page: number) {
  return useQuery({
    queryKey: ['products', filter, page],
    queryFn: () =>
      gql<{ products: { items: ProductSummary[]; total: number; has_more: boolean } }>(
        ENDPOINT,
        `query Products($filter: ProductFilter, $limit: Int!, $offset: Int!) {
          products(filter: $filter, limit: $limit, offset: $offset) { items { ${SUMMARY} } total has_more }
        }`,
        { filter, limit: PAGE_SIZE, offset: page * PAGE_SIZE },
      ).then((d) => d.products),
    placeholderData: keepPreviousData,
  });
}

export function useProduct(productId: number | null) {
  return useQuery({
    queryKey: ['product', productId],
    enabled: productId !== null,
    queryFn: () =>
      gql<{ product: Product | null }>(ENDPOINT, `query Product($id: Int!) { product(product_id: $id) { ${FULL} } }`, {
        id: productId,
      }).then((d) => d.product),
  });
}

/** Produit lu en GraphQL → valeurs du formulaire. */
export function toForm(p: Product): FormValues {
  const attrs = emptyAttributes(p.category);
  for (const f of ATTRIBUTE_FIELDS[p.category]) {
    const source = f.key === 'type' ? (TYPE_ALIAS[p.category] ?? 'type') : f.key;
    const v = p.attributes[source];
    if (v === null || v === undefined) continue;
    attrs[f.key] = f.kind === 'bool' ? Boolean(v) : Array.isArray(v) ? v.join(', ') : String(v);
  }
  return {
    name: p.name,
    category: p.category,
    price: centsToInput(p.price_cents),
    description: p.description ?? '',
    tags: p.tags.join(', '),
    attributes: attrs,
  };
}

export type InitialStock = { warehouse_id: number; quantity: number };

type SaveArgs =
  | { mode: 'create'; category: Category; product: ParsedProduct; initialStock: InitialStock | null }
  | { mode: 'update'; productId: number; category: Category; product: ParsedProduct };

export function useSaveProduct() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: SaveArgs) => {
      const input = { ...args.product, category: args.category };
      if (args.mode === 'create') {
        return gql<{ createProduct: { product_id: number } }>(
          ENDPOINT,
          `mutation Create($input: ProductInput!, $stock: InitialStock) {
            createProduct(input: $input, initial_stock: $stock) { product_id }
          }`,
          { input, stock: args.initialStock },
        ).then((d) => d.createProduct.product_id);
      }
      return gql<{ updateProduct: { product_id: number } }>(
        ENDPOINT,
        `mutation Update($id: Int!, $input: ProductUpdateInput!) { updateProduct(product_id: $id, input: $input) { product_id } }`,
        { id: args.productId, input },
      ).then((d) => d.updateProduct.product_id);
    },
    onSuccess: (productId) => {
      qc.invalidateQueries({ queryKey: ['products'] });
      qc.invalidateQueries({ queryKey: ['product', productId] });
      qc.invalidateQueries({ queryKey: ['stock'] });
    },
  });
}

/** Archiver (on ne supprime pas un produit déjà commandé) ou réactiver. */
export function useSetStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ productId, archive }: { productId: number; archive: boolean }) =>
      gql(
        ENDPOINT,
        archive
          ? 'mutation($id: Int!) { archiveProduct(product_id: $id) { product_id } }'
          : 'mutation($id: Int!) { reactivateProduct(product_id: $id) { product_id } }',
        { id: productId },
      ),
    onSuccess: (_d, { productId }) => {
      qc.invalidateQueries({ queryKey: ['products'] });
      qc.invalidateQueries({ queryKey: ['product', productId] });
    },
  });
}

/** Plusieurs produits en un appel, dans l'ordre demandé ; les ids inconnus sont ignorés (200 max). */
export function useProductsByIds(ids: number[]) {
  return useQuery({
    queryKey: ['products', 'byIds', ids],
    enabled: ids.length > 0,
    queryFn: () =>
      gql<{ productsByIds: ProductSummary[] }>(
        ENDPOINT,
        `query ByIds($ids: [Int!]!) { productsByIds(ids: $ids) { ${SUMMARY} } }`,
        { ids },
      ).then((d) => d.productsByIds),
  });
}

/** Nombre de produits actifs (seul `total` est lu : limit 1). */
export function useActiveProductCount() {
  return useQuery({
    queryKey: ['products', 'activeCount'],
    queryFn: () =>
      gql<{ products: { total: number } }>(
        ENDPOINT,
        'query { products(filter: { status: active }, limit: 1) { total } }',
      ).then((d) => d.products.total),
    refetchInterval: 60_000,
  });
}