-- =========================================================
-- AfricansBiz — 004 : espace annonceurs + récompenses vidéo
-- =========================================================

-- Annonces payées par des annonceurs (bannière texte + lien)
create table if not exists public.annonces (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users on delete cascade,
  titre       text not null check (char_length(titre) between 3 and 60),
  texte       text not null check (char_length(texte) between 10 and 160),
  lien        text not null check (lien ~ '^https://[^[:space:]]+$' and char_length(lien) <= 300),
  formule     text not null,
  jours       int  not null check (jours between 1 and 90),
  prix        int  not null check (prix > 0),
  statut      text not null default 'a_payer'
              check (statut in ('a_payer','en_revue','active','refusee','expiree')),
  motif       text,
  debut       timestamptz,
  fin         timestamptz,
  vues        int not null default 0,
  clics       int not null default 0,
  created_at  timestamptz not null default now()
);
alter table public.annonces enable row level security;
create index if not exists annonces_actives on public.annonces (statut, fin);
create index if not exists annonces_user on public.annonces (user_id, created_at desc);

alter table public.payments add column if not exists annonce_id uuid references public.annonces on delete set null;

-- Récompenses des pubs vidéo (AdMob, vérifiées côté serveur). id = transaction AdMob : anti-rejeu.
create table if not exists public.recompenses (
  id          text primary key,
  user_id     uuid not null references auth.users on delete cascade,
  created_at  timestamptz not null default now()
);
alter table public.recompenses enable row level security;
create index if not exists recompenses_user on public.recompenses (user_id, created_at desc);

-- Vue / clic sur une annonce
create or replace function public.annonce_compter(p_id uuid, p_clic boolean)
returns void language sql security definer set search_path = public as $$
  update annonces set vues = vues + (case when p_clic then 0 else 1 end),
                      clics = clics + (case when p_clic then 1 else 0 end)
   where id = p_id and statut = 'active';
$$;

-- +1 crédit par vidéo regardée, 3 par jour maximum.
-- Retourne le nouveau solde, -1 si limite atteinte, -2 si transaction déjà comptée.
create or replace function public.accorder_recompense(p_tx text, p_user uuid)
returns int language plpgsql security definer set search_path = public as $$
declare c int;
begin
  perform 1 from profiles where id = p_user for update;
  if not found then return -3; end if;
  if (select count(*) from recompenses where user_id = p_user and created_at > now() - interval '1 day') >= 3 then
    return -1;
  end if;
  insert into recompenses (id, user_id) values (p_tx, p_user) on conflict do nothing;
  if not found then return -2; end if;
  update profiles set credits = credits + 1 where id = p_user returning credits into c;
  return c;
end $$;

-- Location : les VIP, ou n'importe qui ayant des crédits (gagnés en regardant des vidéos)
create or replace function public.rent_number(p_user uuid, p_country text, p_minutes integer default 20)
returns numbers language plpgsql security definer set search_path = public as $$
declare n public.numbers; p public.profiles; vip boolean;
begin
  select * into p from profiles where id = p_user for update;
  if p is null then raise exception 'VIP_REQUIRED'; end if;
  vip := p.plan <> 'free' and (p.plan_until is null or p.plan_until > now());
  if p.credits <= 0 then
    if vip then raise exception 'NO_CREDITS'; else raise exception 'VIP_REQUIRED'; end if;
  end if;

  select * into n from numbers
   where active and country = p_country
     and (rented_until is null or rented_until < now())
     and (last_seen_at is null or last_seen_at > now() - interval '15 minutes')
   order by is_public asc, random()
   limit 1 for update skip locked;
  if n is null then raise exception 'NO_NUMBER'; end if;

  update numbers set rented_by = p_user, rented_until = now() + make_interval(mins => p_minutes)
   where id = n.id returning * into n;
  update profiles set credits = credits - 1 where id = p_user;
  insert into rentals (user_id, number_id, ends_at) values (p_user, n.id, n.rented_until);
  return n;
end $$;

-- Paiement confirmé : commande, annonce ou pack VIP
create or replace function public.apply_payment(p_tx text)
returns void language plpgsql security definer set search_path = public as $$
declare pay public.payments; days int; cr int;
begin
  select * into pay from payments where provider_tx = p_tx for update;
  if pay is null or pay.status = 'approved' then return; end if;
  update payments set status = 'approved', paid_at = now() where id = pay.id;

  if pay.order_id is not null then
    update orders set status = 'payee', updated_at = now(), admin_unread = admin_unread + 1
     where id = pay.order_id and status in ('a_payer','devis');
    insert into order_events (order_id, kind, label, detail)
      values (pay.order_id, 'status', 'Paiement confirmé', pay.amount || ' F reçus par Mobile Money');
    return;
  end if;

  if pay.annonce_id is not null then
    update annonces set statut = 'en_revue' where id = pay.annonce_id and statut = 'a_payer';
    return;
  end if;

  days := case pay.pack when 'bronze' then 7 else 30 end;
  cr   := case pay.pack when 'bronze' then 3 when 'argent' then 15 when 'or' then 50 else 0 end;
  update profiles set
    plan = pay.pack,
    plan_until = greatest(coalesce(plan_until, now()), now()) + make_interval(days => days),
    credits = credits + cr
  where id = pay.user_id;
end $$;

revoke execute on function public.annonce_compter(uuid, boolean) from public, anon, authenticated;
revoke execute on function public.accorder_recompense(text, uuid) from public, anon, authenticated;
revoke execute on function public.rent_number(uuid, text, integer) from public, anon, authenticated;
revoke execute on function public.apply_payment(text) from public, anon, authenticated;

-- Les annonces arrivées à terme passent en « expirée » (toutes les heures)
select cron.schedule('africansbiz-annonces', '7 * * * *',
  $$update public.annonces set statut = 'expiree' where statut = 'active' and fin < now()$$);
