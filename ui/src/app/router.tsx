import { createBrowserRouter, redirect, type LoaderFunctionArgs } from 'react-router';
import { Layout } from './Layout';
import { ensureSession } from '../auth/auth';
import { CatalogPage } from '../features/catalog/CatalogPage';
import { Pending } from '../pages/Pending';
import { LoginPage } from '../pages/LoginPage';
import { OverviewPage } from '../features/overview/OverviewPage';
import { ObservabilityPage } from '../features/observability/ObservabilityPage';
import { LogisticsPage } from '../features/logistics/LogisticsPage';

/** Toutes les pages sauf /login : session valide (au besoin renouvelée), sinon redirection vers /login. */
async function requireSession({ request }: LoaderFunctionArgs) {
  if (await ensureSession()) return null;
  const url = new URL(request.url);
  throw redirect(`/login?next=${encodeURIComponent(url.pathname + url.search)}`);
}

/** Déjà connecté (ou session récupérable après rechargement) : pas besoin de la page de connexion. */
async function skipIfLoggedIn() {
  return (await ensureSession()) ? redirect('/') : null;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage />, loader: skipIfLoggedIn, HydrateFallback: () => null },
  {
    path: '/',
    element: <Layout />,
    loader: requireSession,
    HydrateFallback: () => null,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'commandes', element: <Pending title="Commandes" waitingFor="services Commandes et Paiement" /> },
      { path: 'catalogue', element: <CatalogPage /> },
      { path: 'logistique', element: <LogisticsPage /> },
      { path: 'observabilite', element: <ObservabilityPage /> },
    ],
  },
]);