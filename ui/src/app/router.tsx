import { createBrowserRouter } from 'react-router';
import { Layout } from './Layout';
import { CatalogPage } from '../features/catalog/CatalogPage';
import { Pending } from '../pages/Pending';
import { OverviewPage } from '../features/overview/OverviewPage';
import { ObservabilityPage } from '../features/observability/ObservabilityPage';
import { LogisticsPage } from '../features/logistics/LogisticsPage';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'commandes', element: <Pending title="Commandes" waitingFor="services Commandes et Paiement" /> },
      { path: 'catalogue', element: <CatalogPage /> },
      { path: 'logistique', element: <LogisticsPage /> },
      { path: 'observabilite', element: <ObservabilityPage /> },
    ],
  },
]);