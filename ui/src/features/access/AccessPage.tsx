import { useState, type FormEvent, type ReactNode } from 'react';
import type { Role } from '../../auth/auth';
import {
  INTERNAL_ROLES,
  PERMISSIONS,
  PERMISSION_LABELS,
  ROLE_LABELS,
  TABS,
  TAB_LABELS,
  roleHas,
  type Permission,
  type Tab,
} from '../../auth/permissions';
import { Badge, Button, Notice, fieldClass } from '../../ui/ui';
import { useAccounts, useApiKeys, useCreateAccount, useRevokeApiKey, useRoles, type Account } from './api';

const DATE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short' });
const when = (iso: string | null) => (iso ? DATE.format(new Date(iso)) : '—');
const label = (role: string) => ROLE_LABELS[role as Role] ?? role;

/** Onglet admin (D10) : matrice des droits, comptes, comptes techniques, création d'un compte. */
export function AccessPage() {
  return (
    <section className="flex flex-col gap-5">
      <div>
        <h1 className="m-0 text-[28px] font-extrabold tracking-tight">Accès & rôles</h1>
        <p className="m-0 text-sm text-muted">Service Auth · schéma PostgreSQL auth (users, roles, user_roles, api_keys)</p>
      </div>
      <RightsMatrix />
      <Accounts />
      <ApiKeys />
    </section>
  );
}

/** Matrice affichée à partir de permissions.ts : ce qui est grisé à l'écran et ce qui est affiché ici ne peuvent pas diverger. */
function RightsMatrix() {
  const rows: { key: Tab | Permission; text: string; group: string }[] = [
    ...TABS.map((t) => ({ key: t, text: TAB_LABELS[t], group: 'Onglets' })),
    ...PERMISSIONS.map((p) => ({ key: p, text: PERMISSION_LABELS[p], group: 'Actions' })),
  ];
  return (
    <Card title="Matrice des droits" hint="Ce que l'écran propose à chaque rôle. Le refus réel vient de l'API : 401 sans jeton valide, 403 si le rôle ne suffit pas.">
      <div className="overflow-x-auto rounded-2xl border border-line">
        <table className="w-full min-w-[640px] border-collapse text-sm">
          <thead>
            <tr className="bg-surface-2 text-left text-xs text-muted">
              <th className="px-4 py-2.5 font-semibold">Droit</th>
              {INTERNAL_ROLES.map((r) => (
                <th key={r} className="px-3 py-2.5 text-center font-semibold">
                  <code>{r}</code>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={row.key} className={`border-t border-line ${i > 0 && rows[i - 1].group !== row.group ? 'border-t-2' : ''}`}>
                <td className="px-4 py-2.5">
                  <span className="mr-2 text-xs text-muted">{row.group === 'Onglets' ? 'Onglet' : 'Action'}</span>
                  {row.text}
                </td>
                {INTERNAL_ROLES.map((r) => (
                  <td key={r} className="px-3 py-2.5 text-center">
                    {roleHas(r, row.key) ? <Badge tone="ok">oui</Badge> : <span className="text-muted" aria-label="non">—</span>}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-xs text-muted">
        <code>customer</code> : lire le catalogue, créer une commande, lire uniquement ses propres commandes (boutique, pas de back-office).{' '}
        <code>service_account</code> : jeton court obtenu avec une clé d'API, pour les appels entre services ; pas de connexion à l'écran.
      </p>
    </Card>
  );
}

type Filter = 'team' | 'customers' | 'all';
const FILTERS: { id: Filter; text: string }[] = [
  { id: 'team', text: 'Équipe' },
  { id: 'customers', text: 'Clients' },
  { id: 'all', text: 'Tous' },
];

function Accounts() {
  const accounts = useAccounts();
  const [filter, setFilter] = useState<Filter>('team');
  const [creating, setCreating] = useState(false);
  const isCustomer = (a: Account) => a.roles.includes('customer');
  const list = (accounts.data ?? []).filter((a) => (filter === 'all' ? true : filter === 'customers' ? isCustomer(a) : !isCustomer(a)));

  return (
    <Card
      title="Comptes"
      hint="Table auth.users : l'équipe et les clients (rôle customer). Mots de passe hachés en bcrypt, jamais affichés."
      action={
        <Button variant="primary" onClick={() => setCreating((c) => !c)} aria-expanded={creating}>
          {creating ? 'Fermer' : '+ Nouveau compte'}
        </Button>
      }
    >
      {creating && <CreateAccount onDone={() => setCreating(false)} />}

      <div role="group" aria-label="Filtrer les comptes" className="flex flex-wrap gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            aria-pressed={filter === f.id}
            onClick={() => setFilter(f.id)}
            className={`min-h-9 cursor-pointer rounded-full px-3.5 text-sm font-semibold transition-colors ${
              filter === f.id ? 'bg-accent text-accent-ink' : 'bg-surface-2 text-muted hover:text-ink'
            }`}
          >
            {f.text}
          </button>
        ))}
      </div>

      {accounts.error && <Notice tone="bad">{accounts.error.message}</Notice>}
      <div className="overflow-x-auto rounded-2xl border border-line">
        <table className="w-full min-w-[560px] border-collapse text-sm">
          <thead>
            <tr className="bg-surface-2 text-left text-xs text-muted">
              <th className="px-4 py-2.5 font-semibold">Email</th>
              <th className="px-4 py-2.5 font-semibold">Rôles</th>
              <th className="px-4 py-2.5 font-semibold">Statut</th>
              <th className="px-4 py-2.5 font-semibold">Créé le</th>
            </tr>
          </thead>
          <tbody>
            {accounts.isPending ? (
              <EmptyRow cols={4} text="Chargement…" />
            ) : list.length === 0 ? (
              <EmptyRow cols={4} text="Aucun compte." />
            ) : (
              list.map((a) => (
                <tr key={a.id} className="border-t border-line">
                  <td className="px-4 py-2.5 font-semibold">{a.email}</td>
                  <td className="px-4 py-2.5">
                    <span className="flex flex-wrap gap-1">
                      {a.roles.map((r) => (
                        <Badge key={r} tone={r === 'customer' ? 'neutral' : 'info'}>
                          {label(r)}
                        </Badge>
                      ))}
                    </span>
                  </td>
                  <td className="px-4 py-2.5">{a.is_active ? <Badge tone="ok">Actif</Badge> : <Badge>Désactivé</Badge>}</td>
                  <td className="px-4 py-2.5 text-muted">{when(a.created_at)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

/** POST /admin/users : email, mot de passe (8 caractères minimum, haché en bcrypt par auth-service), au moins un rôle. */
function CreateAccount({ onDone }: { onDone: () => void }) {
  const roles = useRoles();
  const create = useCreateAccount();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [roleIds, setRoleIds] = useState<number[]>([]);
  const [tried, setTried] = useState(false);

  // service_account : jamais un compte de connexion (clé d'API, D6).
  const choices = (roles.data ?? []).filter((r) => r.name !== 'service_account');
  const errors = {
    email: /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()) ? null : 'Adresse email invalide.',
    password: password.length >= 8 ? null : '8 caractères minimum.',
    roles: roleIds.length > 0 ? null : 'Choisissez au moins un rôle.',
  };
  const valid = !errors.email && !errors.password && !errors.roles;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!valid) return;
    create.mutate({ email: email.trim().toLowerCase(), password, role_ids: roleIds }, { onSuccess: onDone });
  }

  const toggle = (id: number) => setRoleIds((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 rounded-2xl border border-line bg-surface-2 p-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1.5 text-sm font-semibold">
          Email
          <input type="email" autoComplete="off" className={fieldClass} value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={tried && !!errors.email} />
          {tried && errors.email && <span className="text-xs text-bad">{errors.email}</span>}
        </label>
        <label className="flex flex-col gap-1.5 text-sm font-semibold">
          Mot de passe provisoire
          <input type="password" autoComplete="new-password" className={fieldClass} value={password} onChange={(e) => setPassword(e.target.value)} aria-invalid={tried && !!errors.password} />
          {tried && errors.password && <span className="text-xs text-bad">{errors.password}</span>}
        </label>
      </div>

      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="mb-1.5 text-sm font-semibold">Rôles</legend>
        {roles.error && <Notice tone="bad">{roles.error.message}</Notice>}
        <div className="flex flex-wrap gap-2">
          {choices.map((r) => (
            <label key={r.id} title={r.description ?? undefined} className="flex cursor-pointer items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-sm font-semibold">
              <input type="checkbox" checked={roleIds.includes(r.id)} onChange={() => toggle(r.id)} />
              {label(r.name)} <code className="text-xs text-muted">{r.name}</code>
            </label>
          ))}
        </div>
        {tried && errors.roles && <span className="text-xs text-bad">{errors.roles}</span>}
      </fieldset>

      {create.error && <Notice tone="bad">{create.error.message}</Notice>}
      <div className="flex justify-end">
        <Button type="submit" variant="primary" disabled={create.isPending}>
          {create.isPending ? 'Création…' : 'Créer le compte'}
        </Button>
      </div>
    </form>
  );
}

/** Table auth.api_keys : seule l'empreinte SHA-256 est stockée, la clé n'est jamais affichée. Révocation en deux clics. */
function ApiKeys() {
  const keys = useApiKeys();
  const roles = useRoles();
  const revoke = useRevokeApiKey();
  const [confirming, setConfirming] = useState<number | null>(null);
  const roleName = new Map(roles.data?.map((r) => [r.id, r.name]));

  return (
    <Card
      title="Comptes techniques"
      hint="Table auth.api_keys : une clé d'API par service, échangée contre un jeton court (POST /v1/auth/token). Seule l'empreinte de la clé est stockée. Une clé révoquée ne permet plus d'obtenir de jeton ; ceux déjà émis expirent au bout de 15 min."
    >
      {keys.error && <Notice tone="bad">{keys.error.message}</Notice>}
      {revoke.error && <Notice tone="bad">{revoke.error.message}</Notice>}
      <div className="overflow-x-auto rounded-2xl border border-line">
        <table className="w-full min-w-[640px] border-collapse text-sm">
          <thead>
            <tr className="bg-surface-2 text-left text-xs text-muted">
              <th className="px-4 py-2.5 font-semibold">Service</th>
              <th className="px-4 py-2.5 font-semibold">Rôle</th>
              <th className="px-4 py-2.5 font-semibold">Statut</th>
              <th className="px-4 py-2.5 font-semibold">Créée le</th>
              <th className="px-4 py-2.5 font-semibold">Action</th>
            </tr>
          </thead>
          <tbody>
            {keys.isPending ? (
              <EmptyRow cols={5} text="Chargement…" />
            ) : (keys.data ?? []).length === 0 ? (
              <EmptyRow cols={5} text="Aucune clé." />
            ) : (
              keys.data!.map((k) => (
                <tr key={k.id} className="border-t border-line">
                  <td className="px-4 py-2.5 font-semibold">
                    <code>{k.name}</code>
                  </td>
                  <td className="px-4 py-2.5">
                    <Badge tone="info">{label(roleName.get(k.role_id) ?? `#${k.role_id}`)}</Badge>
                  </td>
                  <td className="px-4 py-2.5">{k.is_active ? <Badge tone="ok">Active</Badge> : <Badge>Révoquée</Badge>}</td>
                  <td className="px-4 py-2.5 text-muted">{when(k.created_at)}</td>
                  <td className="px-4 py-2">
                    {!k.is_active ? (
                      <span className="text-xs text-muted">—</span>
                    ) : confirming === k.id ? (
                      <div className="flex flex-col items-start gap-1.5">
                        <span className="text-xs text-bad">
                          <code>{k.name}</code> ne pourra plus obtenir de jeton.
                        </span>
                        <div className="flex gap-2">
                          <Button
                            variant="danger"
                            className="min-h-9 px-3 text-sm"
                            disabled={revoke.isPending}
                            onClick={() => revoke.mutate(k.id, { onSettled: () => setConfirming(null) })}
                          >
                            {revoke.isPending ? '…' : 'Confirmer'}
                          </Button>
                          <Button className="min-h-9 px-3 text-sm" onClick={() => setConfirming(null)}>
                            Garder
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <Button variant="danger" className="min-h-9 px-3 text-sm" onClick={() => setConfirming(k.id)}>
                        Révoquer
                      </Button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function Card({ title, hint, action, children }: { title: string; hint?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-[22px] border border-line bg-surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex max-w-3xl flex-col gap-0.5">
          <h2 className="m-0 text-[17px] font-bold">{title}</h2>
          {hint && <p className="m-0 text-sm text-muted">{hint}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

function EmptyRow({ cols, text }: { cols: number; text: string }) {
  return (
    <tr>
      <td colSpan={cols} className="px-4 py-4 text-muted">
        {text}
      </td>
    </tr>
  );
}