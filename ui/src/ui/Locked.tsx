import type { ReactNode } from 'react';

/**
 * Action interdite au rôle (D9) : l'élément reste visible mais grisé et non cliquable, avec la raison au survol.
 * L'infobulle est portée par ce span, car un bouton désactivé ne reçoit pas le survol dans tous les navigateurs.
 * Le bouton enfant doit aussi recevoir `disabled` : c'est lui qui le grise et le retire du clavier.
 */
export function Locked({ locked, reason, children }: { locked: boolean; reason: string; children: ReactNode }) {
  if (!locked) return <>{children}</>;
  return (
    <span title={reason} className="inline-flex cursor-not-allowed [&>*]:pointer-events-none">
      {children}
    </span>
  );
}