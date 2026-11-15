-- =========================================================
-- TempoAfrik — schéma de base de données (Supabase / Postgres)
-- À coller dans Supabase > SQL Editor, puis "Run".
-- Toutes les tables sont fermées au public (RLS sans politique) :
-- seul le code serveur (Edge Functions, clé service_role) y accède.
-- =========================================================

create extension if not exists pgcrypto;
create extension if not exists pg_cron;

-- Profils (un par utilisateur connecté)
create table if not exists public.profiles (
  id          uuid primary key references auth.users(id) on delete cascade,
  email       text,
  plan        text not null default 'free' check (plan in ('free','bronze','argent','or')),
  plan_until  timestamptz,
  credits     int  not null default 0 check (credits >= 0),
  banned      boolean not null default false,
  created_at  timestamptz not null default now()
);

-- Création automatique du profil à l'inscription
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email) values (new.id, new.email) on conflict do nothing;
  return new;
end $$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- Boîtes mail temporaires
create table if not exists public.mailboxes (
  id          uuid primary key default gen_random_uuid(),
  address     text not null unique,              -- toujours en minuscules
  token       text not null,                     -- secret pour lire la boîte
  owner       uuid references auth.users(id) on delete set null,
  ip_hash     text,
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);
create index if not exists mailboxes_expires_idx on public.mailboxes (expires_at);
create index if not exists mailboxes_ip_idx on public.mailboxes (ip_hash, created_at);

-- Mails reçus
create table if not exists public.emails (
  id          uuid primary key default gen_random_uuid(),
  mailbox_id  uuid not null references public.mailboxes(id) on delete cascade,
  from_addr   text,
  subject     text,
  body_text   text,
  body_html   text,
  received_at timestamptz not null default now()
);
create index if not exists emails_mailbox_idx on public.emails (mailbox_id, received_at desc);

-- Numéros de réception SMS
create table if not exists public.numbers (
  id           uuid primary key default gen_random_uuid(),
  e164         text not null unique,            -- ex : +2290197123456
  country      text not null,                   -- code ISO : BJ, TG, CI…
  source       text not null default 'sim',     -- 'sim' (téléphone + Termux) ou nom du fournisseur
  is_public    boolean not null default true,   -- false = réservé à la location privée
  active       boolean not null default true,
  rented_by    uuid references auth.users(id) on delete set null,
  rented_until timestamptz,
  last_seen_at timestamptz,                     -- dernier signal du téléphone relais
  created_at   timestamptz not null default now()
);
create index if not exists numbers_country_idx on public.numbers (country) where active;

-- SMS reçus
create table if not exists public.sms (
  id          uuid primary key default gen_random_uuid(),
  number_id   uuid not null references public.numbers(id) on delete cascade,
  sender      text,
  body        text not null,
  owner       uuid references auth.users(id) on delete set null,  -- rempli si le numéro était loué
  dedupe      text unique,                                       -- évite les doublons du relais
  received_at timestamptz not null default now()
);
create index if not exists sms_number_idx on public.sms (number_id, received_at desc);

-- Locations de numéros privés (historique)
create table if not exists public.rentals (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  number_id   uuid not null references public.numbers(id) on delete cascade,
  started_at  timestamptz not null default now(),
  ends_at     timestamptz not null
);

-- Paiements
create table if not exists public.payments (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  pack         text not null,
  amount       int  not null,
  provider     text not null default 'saspay',
  provider_tx  text unique,
  status       text not null default 'pending',  -- pending | approved | failed
  created_at   timestamptz not null default now(),
  paid_at      timestamptz
);

-- Sécurité : RLS activé, aucune politique => aucun accès direct depuis le navigateur
alter table public.profiles  enable row level security;
alter table public.mailboxes enable row level security;
alter table public.emails    enable row level security;
alter table public.numbers   enable row level security;
alter table public.sms       enable row level security;
alter table public.rentals   enable row level security;
alter table public.payments  enable row level security;

-- Location atomique d'un numéro (évite que 2 personnes louent le même)
create or replace function public.rent_number(p_user uuid, p_country text, p_minutes int default 20)
returns public.numbers language plpgsql security definer set search_path = public as $$
declare n public.numbers; p public.profiles;
begin
  select * into p from profiles where id = p_user for update;
  if p is null or p.plan = 'free' or (p.plan_until is not null and p.plan_until < now()) then
    raise exception 'VIP_REQUIRED';
  end if;
  if p.credits <= 0 then raise exception 'NO_CREDITS'; end if;

  select * into n from numbers
   where active and country = p_country
     and (rented_until is null or rented_until < now())
     and (last_seen_at is null or last_seen_at > now() - interval '15 minutes')
   order by is_public asc, random()       -- d'abord les numéros réservés au privé
   limit 1 for update skip locked;
  if n is null then raise exception 'NO_NUMBER'; end if;

  update numbers set rented_by = p_user, rented_until = now() + make_interval(mins => p_minutes)
   where id = n.id returning * into n;
  update profiles set credits = credits - 1 where id = p_user;
  insert into rentals (user_id, number_id, ends_at) values (p_user, n.id, n.rented_until);
  return n;
end $$;
revoke all on function public.rent_number(uuid, text, int) from public, anon, authenticated;

-- Activation d'un pack après paiement confirmé (idempotent)
create or replace function public.apply_payment(p_tx text)
returns void language plpgsql security definer set search_path = public as $$
declare pay public.payments; days int; cr int;
begin
  select * into pay from payments where provider_tx = p_tx for update;
  if pay is null or pay.status = 'approved' then return; end if;
  days := case pay.pack when 'bronze' then 7 else 30 end;
  cr   := case pay.pack when 'bronze' then 3 when 'argent' then 15 when 'or' then 50 else 0 end;
  update payments set status = 'approved', paid_at = now() where id = pay.id;
  update profiles set
    plan = pay.pack,
    plan_until = greatest(coalesce(plan_until, now()), now()) + make_interval(days => days),
    credits = credits + cr
  where id = pay.user_id;
end $$;
revoke all on function public.apply_payment(text) from public, anon, authenticated;

-- Nettoyage automatique toutes les 10 minutes
select cron.schedule('tempoafrik-cleanup', '*/10 * * * *', $$
  delete from public.mailboxes where expires_at < now();
  delete from public.sms where received_at < now() - interval '24 hours';
  update public.numbers set rented_by = null, rented_until = null where rented_until < now();
  update public.profiles set plan = 'free', credits = 0 where plan <> 'free' and plan_until < now();
$$);
