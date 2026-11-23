import type { ReactElement } from 'react';
import { createBrowserRouter, redirect, type LoaderFunctionArgs } from 'react-router';
import { Layout } from './Layout';
import { GESTION, GESTION_LOGIN } from './paths';
import { ensureSession, getRoles, logout } from '../auth/auth';
import { canTab, isInternal, type Tab } from '../auth/permissions';
import { CatalogPage } from '../features/catalog/CatalogPage';
import { AccessPage } from '../features/access/AccessPage';
import { LoginPage } from '../pages/LoginPage';
import { AccessDenied } from '../pages/AccessDenied';
import { OverviewPage } from '../features/overview/OverviewPage';
import { ObservabilityPage } from '../features/observability/ObservabilityPage';
import { LogisticsPage } from '../features/logistics/LogisticsPage';
import { OrdersPage } from '../features/orders/OrdersPage';

/**
 * Espace de gestion : session valide (au besoin renouvelée) ET rôle de l'équipe.
 * Sans session : page de connexion. Compte client (customer) : déconnecté et renvoyé vers la connexion avec une explication.
 */
async function requireSession({ request }: LoaderFunctionArgs) {
  if (await ensureSession()) {
    if (isInternal(getRoles())) return null;
    await logout();
    throw redirect(`${GESTION_LOGIN}?reason=client`);
  }
  const url = new URL(request.url);
  throw redirect(`${GESTION_LOGIN}?next=${encodeURIComponent(url.pathname + url.search)}`);
}

/** Déjà connecté avec un compte de l'équipe : pas besoin de la page de connexion. */
async function skipIfLoggedIn() {
  return (await ensureSession()) && isInternal(getRoles()) ? redirect(GESTION) : null;
}

/** Onglet réservé : la page s'affiche seulement si le rôle y a droit (le lien du menu, lui, est grisé). */
function guard(tab: Tab, page: ReactElement) {
  const Guarded = () => (canTab(getRoles(), tab) ? page : <AccessDenied tab={tab} />);
  return <Guarded />;
}

export const router = createBrowserRouter([
  // Boutique (D9) : à venir. En attendant, la racine mène à l'espace de gestion.
  { path: '/', loader: () => redirect(GESTION) },
  { path: GESTION_LOGIN, element: <LoginPage />, loader: skipIfLoggedIn, HydrateFallback: () => null },
  {
    path: GESTION,
    element: <Layout />,
    loader: requireSession,
    HydrateFallback: () => null,
    children: [
      { index: true, element: guard('overview', <OverviewPage />) },
      { path: 'commandes', element: guard('orders', <OrdersPage />) },
      { path: 'catalogue', element: guard('catalog', <CatalogPage />) },
      { path: 'logistique', element: guard('logistics', <LogisticsPage />) },
      { path: 'observabilite', element: guard('observability', <ObservabilityPage />) },
      { path: 'acces', element: guard('access', <AccessPage />) },
    ],
  },
]);