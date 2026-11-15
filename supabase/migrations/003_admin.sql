-- AfricansBiz : rôle administrateur automatique + durcissement (appliqué le 15/11/2026)
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email, role)
  values (new.id, new.email, case when lower(new.email) = 'bienstani@gmail.com' then 'admin' else 'client' end)
  on conflict do nothing;
  return new;
end $$;
revoke execute on function public.handle_new_user() from public, anon, authenticated;
update public.profiles set role = 'admin' where lower(email) = 'bienstani@gmail.com';
