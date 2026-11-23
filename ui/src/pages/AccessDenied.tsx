import { Link } from 'react-router';
import { getRoles } from '../auth/auth';
import { ROLE_LABELS, TAB_LABELS, reservedTo, type Tab } from '../auth/permissions';
import { Badge } from '../ui/ui';
import { GESTION } from '../app/paths';

/** Onglet interdit atteint par son adresse (le lien du menu, lui, est grisé). Le refus réel reste celui de l'API (403). */
export function AccessDenied({ tab }: { tab: Tab }) {
  const roles = getRoles();
  return (
    <section className="mx-auto flex max-w-lg flex-col items-center gap-4 py-16 text-center">
      <Badge tone="bad">403</Badge>
      <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Accès refusé</h1>
      <p className="m-0 text-muted">
        L'onglet « {TAB_LABELS[tab]} » n'est pas ouvert à votre rôle ({roles.map((r) => ROLE_LABELS[r] ?? r).join(', ') || 'aucun'}).
        <br />
        {reservedTo(tab)}.
      </p>
      <Link
        to={GESTION}
        className="inline-flex min-h-11 items-center rounded-full border border-line bg-surface px-4 font-semibold text-ink no-underline transition-colors hover:bg-surface-2"
      >
        Retour à la vue d'ensemble
      </Link>
    </section>
  );
}