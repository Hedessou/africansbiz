-- =========================================================
-- AfricansBiz — commandes, suivi, chat sécurisé, administration
-- À exécuter APRÈS 001_schema.sql (SQL Editor → Run)
-- =========================================================

-- Rôle et infos client
alter table public.profiles add column if not exists role text not null default 'client' check (role in ('client','admin'));
alter table public.profiles add column if not exists full_name text;
alter table public.profiles add column if not exists whatsapp text;

-- Catalogue de services (modifiable depuis la base sans redéployer le site)
create table if not exists public.services (
  id          text primary key,                 -- ex : robot-whatsapp
  fam         text not null,                    -- creer | automatiser | gerer | installer | rediger
  nom         text not null,
  court       text not null,
  prix        int  not null check (prix >= 0),  -- prix fixe, ou "à partir de" si type = devis
  type        text not null check (type in ('fixe','devis')),
  unite       text,                             -- ex : "/ mois"
  delai       text not null,
  q           jsonb not null default '[]',      -- questions du formulaire [{k,label,ph}]
  actif       boolean not null default true,
  ordre       int not null default 100
);

-- Commandes
create sequence if not exists public.order_seq start 1;
create table if not exists public.orders (
  id           uuid primary key default gen_random_uuid(),
  ref          text not null unique,
  client       uuid not null references auth.users(id) on delete cascade,
  service_id   text not null references public.services(id),
  brief        text not null,
  answers      jsonb not null default '{}',
  whatsapp     text,
  amount       int check (amount is null or amount > 0),
  status       text not null default 'devis'
               check (status in ('devis','a_payer','payee','en_cours','validation','livree','annulee')),
  admin_unread int not null default 0,          -- messages client non lus par l'admin
  client_unread int not null default 0,         -- messages admin non lus par le client
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index if not exists orders_client_idx on public.orders (client, updated_at desc);
create index if not exists orders_status_idx on public.orders (status, updated_at desc);

-- Référence lisible : AB-AAMM-0001
create or replace function public.next_order_ref() returns text language sql as $$
  select 'AB-' || to_char(now(), 'YYMM') || '-' || lpad(nextval('public.order_seq')::text, 4, '0');
$$;

-- Historique (étapes, notes, livrables)
create table if not exists public.order_events (
  id         uuid primary key default gen_random_uuid(),
  order_id   uuid not null references public.orders(id) on delete cascade,
  kind       text not null check (kind in ('status','note','delivery')),
  label      text not null,
  detail     text,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index if not exists order_events_idx on public.order_events (order_id, created_at);

-- Messages du chat (un fil par commande)
create table if not exists public.messages (
  id         uuid primary key default gen_random_uuid(),
  order_id   uuid not null references public.orders(id) on delete cascade,
  sender     uuid not null references auth.users(id) on delete cascade,
  sender_role text not null check (sender_role in ('client','admin')),
  body       text not null check (length(body) between 1 and 4000),
  created_at timestamptz not null default now()
);
create index if not exists messages_order_idx on public.messages (order_id, created_at);

-- Paiements : une ligne peut concerner un pack OU une commande
alter table public.payments alter column pack drop not null;
alter table public.payments add column if not exists order_id uuid references public.orders(id) on delete set null;

-- Sécurité : tout passe par les Edge Functions (clé service_role) qui vérifient
-- que l'utilisateur est le client de la commande ou un administrateur.
alter table public.services     enable row level security;
alter table public.orders       enable row level security;
alter table public.order_events enable row level security;
alter table public.messages     enable row level security;
-- Le catalogue peut être lu par tout le monde
drop policy if exists services_lecture on public.services;
create policy services_lecture on public.services for select using (actif);

-- Activation après paiement : pack VIP OU commande (remplace la version de 001)
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

  days := case pay.pack when 'bronze' then 7 else 30 end;
  cr   := case pay.pack when 'bronze' then 3 when 'argent' then 15 when 'or' then 50 else 0 end;
  update profiles set
    plan = pay.pack,
    plan_until = greatest(coalesce(plan_until, now()), now()) + make_interval(days => days),
    credits = credits + cr
  where id = pay.user_id;
end $$;
revoke all on function public.apply_payment(text) from public, anon, authenticated;

-- ---------------------------------------------------------
-- Catalogue de départ (prix à ajuster librement)
-- ---------------------------------------------------------
insert into public.services (id, fam, nom, court, prix, type, unite, delai, q, ordre) values
('site-vitrine','creer','Site web vitrine','Un site rapide et beau pour présenter ton activité, avec formulaire de contact et WhatsApp.',50000,'devis',null,'7 à 10 jours','[{"k":"activite","label":"Nom de ton activité","ph":"Ex : Boutique Awa Couture"},{"k":"pages","label":"Pages souhaitées","ph":"Accueil, services, contact…"}]',10),
('boutique','creer','Boutique en ligne','Vends en ligne avec paiement Mobile Money intégré et gestion des commandes.',150000,'devis',null,'2 à 3 semaines','[{"k":"produits","label":"Nombre de produits environ","ph":"Ex : 40"},{"k":"activite","label":"Nom de ta boutique","ph":""}]',20),
('app-android','creer','Application Android','Une vraie application pour tes clients ou ton équipe, publiée en APK ou sur le Play Store.',150000,'devis',null,'3 à 6 semaines','[{"k":"fonctions","label":"Fonctions principales","ph":"Connexion, catalogue, notifications…"},{"k":"public","label":"Pour qui ?","ph":"Clients, employés…"}]',30),
('robot-whatsapp','creer','Robot WhatsApp','Il répond à tes clients, prend les commandes et envoie des rapports, 24 h/24.',40000,'devis',null,'5 à 10 jours','[{"k":"taches","label":"Que doit faire le robot ?","ph":"Répondre aux prix, prendre les commandes…"},{"k":"numero","label":"Numéro WhatsApp dédié ?","ph":"Oui / non"}]',40),
('robot-telegram','creer','Robot Telegram','Un robot avec menus à boutons, commandes réservées à l''admin et alertes en temps réel.',25000,'devis',null,'3 à 7 jours','[{"k":"taches","label":"Que doit faire le robot ?","ph":""}]',50),
('chaine-video','automatiser','Chaîne de production vidéo','Tes vidéos créées et publiées automatiquement chaque jour sur TikTok et YouTube.',75000,'devis',null,'1 à 2 semaines','[{"k":"theme","label":"Thème des vidéos","ph":"Contes, histoire, produits…"},{"k":"rythme","label":"Combien par jour ?","ph":"Ex : 2"}]',60),
('automatisation','automatiser','Automatisation Make','Connecte tes outils entre eux : formulaires, Google Sheets, WhatsApp, emails…',30000,'devis',null,'3 à 7 jours','[{"k":"outils","label":"Outils à connecter","ph":""}]',70),
('collecte','automatiser','Collecte de données et tableau de bord','Récupère automatiquement tes données et suis-les sur un tableau de bord clair.',50000,'devis',null,'1 à 2 semaines','[{"k":"source","label":"D''où viennent les données ?","ph":""}]',80),
('hebergement','gerer','Mise en ligne et hébergement','Nom de domaine, serveur, base de données Supabase : on met ton projet en ligne.',20000,'devis',null,'1 à 3 jours','[{"k":"projet","label":"Quel projet mettre en ligne ?","ph":""}]',90),
('maintenance','gerer','Gestion et maintenance mensuelle','Mises à jour, sauvegardes, corrections et petites évolutions de ton site ou de ton app.',15000,'fixe','/ mois','Démarre sous 48 h','[{"k":"lien","label":"Lien du site ou nom de l''app","ph":""}]',100),
('app-gestion','gerer','Application de gestion d''entreprise','Stock, maintenance, équipes, rapports automatiques sur WhatsApp : tout dans une app.',200000,'devis',null,'4 à 8 semaines','[{"k":"domaine","label":"Ton secteur","ph":"Atelier, commerce, école…"}]',110),
('claude-code','installer','Claude Code sur Android','Installation guidée à distance dans Termux, prête à coder depuis ton téléphone. Abonnement Claude à ta charge.',5000,'fixe',null,'Le jour même','[{"k":"telephone","label":"Modèle de téléphone et version d''Android","ph":"Ex : Redmi Note 13, Android 14"}]',120),
('antigravity','installer','Antigravity CLI sur Android','Installation de l''agent Google Antigravity (agy) dans Termux, avec ton compte Google.',5000,'fixe',null,'Le jour même','[{"k":"telephone","label":"Modèle de téléphone et version d''Android","ph":""}]',130),
('assistant-ia','installer','Assistant IA connecté','Ton assistant IA sur téléphone, relié à Telegram et à tes outils pour travailler à ta place.',15000,'fixe',null,'1 à 2 jours','[{"k":"usage","label":"Ce que l''assistant doit faire","ph":""}]',140),
('acces-vps','installer','Ton serveur depuis ton téléphone','Accès SSH sécurisé à ton serveur ou VPS depuis Termux, avec raccourcis prêts.',10000,'fixe',null,'Le jour même','[{"k":"serveur","label":"Type de serveur","ph":"Windows VPS, Linux…"}]',150),
('dossier-projet','rediger','Dossier de projet ou candidature','Un dossier clair et convaincant pour un concours, un appel à projets ou un financement.',20000,'devis',null,'3 à 7 jours','[{"k":"concours","label":"Concours ou organisme visé","ph":""},{"k":"date","label":"Date limite","ph":"jj/mm/aaaa"}]',160),
('plateforme-ecole','rediger','Plateforme pour école ou étudiants','Banque d''épreuves, cours, challenges et portfolios pour ton établissement.',150000,'devis',null,'3 à 6 semaines','[{"k":"ecole","label":"Établissement","ph":""}]',170)
on conflict (id) do nothing;

-- ---------------------------------------------------------
-- Te donner le rôle administrateur (remplace par TON email,
-- après t'être connecté une première fois sur le site) :
--   update public.profiles set role = 'admin' where email = 'ton.email@gmail.com';
-- ---------------------------------------------------------
