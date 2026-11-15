-- Réglages privés du serveur (lus uniquement par les Edge Functions avec la clé service_role)
create table if not exists public.reglages (
  cle    text primary key,
  valeur text not null,
  maj    timestamptz not null default now()
);
alter table public.reglages enable row level security;
revoke all on public.reglages from anon, authenticated;
-- Secret partagé avec le Worker e-mail et le relais SMS (généré une seule fois)
insert into public.reglages (cle, valeur)
values ('inbound_secret', encode(extensions.gen_random_bytes(32), 'hex'))
on conflict (cle) do nothing;
