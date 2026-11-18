export function Pending({ title, waitingFor }: { title: string; waitingFor: string }) {
  return (
    <section className="flex flex-col gap-2">
      <h1 className="m-0 text-[28px] font-extrabold tracking-tight">{title}</h1>
      <p className="m-0 rounded-[18px] border border-line bg-surface p-5 text-muted">
        Onglet en attente : {waitingFor}.
      </p>
    </section>
  );
}