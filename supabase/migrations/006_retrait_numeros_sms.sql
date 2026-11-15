-- Les numéros SMS sont retirés du projet (risque juridique : SIM enregistrées au nom du propriétaire)
drop function if exists public.rent_number(uuid, text, integer);
drop table if exists public.rentals;
drop table if exists public.sms;
drop table if exists public.numbers;

-- La vidéo récompensée prolonge maintenant la boîte mail temporaire de 24 h (3 fois par jour et par adresse)
alter table public.recompenses alter column user_id drop not null;
alter table public.recompenses add column if not exists adresse text;
create index if not exists recompenses_adresse on public.recompenses (adresse, created_at desc);

drop function if exists public.accorder_recompense(text, uuid);
create or replace function public.accorder_recompense(p_tx text, p_adresse text, p_user uuid default null)
returns timestamptz language plpgsql security definer set search_path = public as $$
declare mb public.mailboxes;
begin
  select * into mb from mailboxes where address = lower(trim(p_adresse)) for update;
  if mb is null or mb.expires_at < now() then return null; end if;
  if (select count(*) from recompenses where adresse = mb.address and created_at > now() - interval '1 day') >= 3 then
    return mb.expires_at;
  end if;
  insert into recompenses (id, user_id, adresse) values (p_tx, p_user, mb.address) on conflict do nothing;
  if not found then return mb.expires_at; end if;
  update mailboxes set expires_at = expires_at + interval '24 hours' where id = mb.id returning expires_at into mb.expires_at;
  return mb.expires_at;
end $$;
revoke execute on function public.accorder_recompense(text, text, uuid) from public, anon, authenticated;

-- Nettoyage périodique sans les numéros
select cron.schedule('africansbiz-cleanup', '*/10 * * * *', $$
  delete from public.mailboxes where expires_at < now();
  update public.profiles set plan = 'free' where plan <> 'free' and plan_until < now();
$$);
