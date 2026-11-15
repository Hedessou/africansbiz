# AfricansBiz — site web + application Android

Une seule plateforme, deux portes d'entrée : **africansbiz.com** et l'**application Android**. Même compte, mêmes commandes, mêmes discussions.

- **Services à commander** (prix fixe ou devis) : sites, boutiques, applications Android, robots WhatsApp et Telegram, chaînes de production vidéo, automatisations Make, collecte de données, hébergement, maintenance, applications de gestion, installation de Claude Code / Antigravity CLI / assistants IA sur Android, accès VPS, dossiers de projet, plateformes pour écoles.
- **Suivi de livraison** : chaque commande a un *bordereau* et un fil de 6 perles (Commande → Devis → Paiement → Réalisation → Validation → Livrée), un historique et des livrables.
- **Chat sécurisé par commande** : seuls le client et toi (administrateur) le voient.
- **Paiement SasPay sans redirection** : la demande arrive sur le téléphone du client (MTN, Moov, Celtiis…).
- **Outils** : email temporaire (prolongeable par vidéo dans l'app), packs VIP, espace annonceurs.
- **Administration** : envoyer un devis, faire avancer les étapes, livrer, répondre aux clients (site ET application).

## Contenu

| Dossier | Rôle |
|---|---|
| `site/index.html` | Le site complet (un fichier). Mode démo tant que `CONFIG` est vide. |
| `android/` | Application Android (Kotlin, Jetpack Compose, Material 3). Mode démo tant que les secrets ne sont pas définis. |
| `.github/workflows/android.yml` | Compile l'APK sur GitHub à chaque modification et le publie dans *Releases*. |
| `supabase/migrations/001_schema.sql` … `006_*.sql` | Mails, packs, paiements, commandes, annonces, récompenses vidéo. |
| `supabase/migrations/002_commandes.sql` | Catalogue, commandes, historique, chat, rôles. Catalogue de 17 services déjà rempli. |
| `supabase/functions/api` | L'API utilisée par le site ET l'application. |
| `supabase/functions/pay-webhook` | Notifications SasPay (signature vérifiée). |
| `supabase/functions/inbound-email` | Réception des mails temporaires. |
| `supabase/functions/admob-reward` | Vérification des vidéos récompensées AdMob (+24 h sur l'adresse). |
| `cloudflare-email-worker/` | Réception des mails (Cloudflare Email Routing). |

## Mise en route

### 1. Supabase
1. Crée un projet sur supabase.com.
2. **SQL Editor** : exécute `001_schema.sql` puis `002_commandes.sql` (active l'extension **pg_cron** si demandé).
3. **Edge Functions → Secrets** :

| Nom | Valeur |
|---|---|
| `SASPAY_SECRET_KEY` | `sk_test_…` pour tester, puis `sk_live_…` (SasPay → Développeur, scope PAYIN) |
| `SASPAY_WEBHOOK_SECRET` | secret affiché une seule fois à la création du webhook SasPay |
| `SASPAY_FEE_MODE` | optionnel : `ADD_ON` (frais payés par le client) ou `DEDUCTED` |
| `PAY_COUNTRIES` | optionnel : `BJ` par défaut, ex. `BJ,TG,CI` |
| `INBOUND_SECRET` | facultatif : sinon le secret généré dans la table `reglages` est utilisé (Worker mails) |
| `MAIL_DOMAINS` | `africansbiz.com` |
| `IP_SALT` | chaîne aléatoire |
| `SITE_ORIGIN` | `https://africansbiz.com` |

4. Déploie les fonctions :
```bash
supabase link --project-ref TON_REF
supabase functions deploy api --no-verify-jwt
supabase functions deploy pay-webhook --no-verify-jwt
supabase functions deploy inbound-email --no-verify-jwt
supabase functions deploy admob-reward --no-verify-jwt
```
5. **Authentication → Email Templates → Magic Link** : ajoute `{{ .Token }}` dans le message pour que le client reçoive un **code à 6 chiffres** (c'est ce que demandent le site et l'app). Exemple de message : « Ton code AfricansBiz : {{ .Token }} ».
6. **Authentication → URL Configuration** : *Site URL* = `https://africansbiz.com`.
7. Connecte-toi une première fois sur le site avec ton email, puis donne-toi le rôle administrateur :
```sql
update public.profiles set role = 'admin' where email = 'ton.email@gmail.com';
```
L'onglet **Administration** apparaît alors sur le site, et le filtre « Toutes (admin) » dans l'application.

### 2. SasPay
1. Webhook : `https://TON_REF.supabase.co/functions/v1/pay-webhook`, événements `transaction.success` et `transaction.failed`. Copie le secret dans `SASPAY_WEBHOOK_SECRET`.
2. Envoie `webhook.test` depuis le tableau de bord, puis fais un achat test avec la clé `sk_test_`.
3. Si un réseau répond avec une page de paiement au lieu d'un push direct, le site ne redirige pas : il demande au client de choisir un autre réseau.

### 3. Domaine africansbiz.com (LWS) + Cloudflare
Le domaine reste chez LWS ; on confie seulement le DNS à Cloudflare (gratuit), nécessaire pour recevoir les emails temporaires.
1. Cloudflare → **Add a site** → `africansbiz.com` (plan Free).
2. LWS → ton domaine → **Gestion des serveurs DNS** → mets les 2 serveurs Cloudflare (jusqu'à 24 h).
3. Cloudflare → **Email Routing** → active-le ; déploie `cloudflare-email-worker` (`npx wrangler deploy`, puis `npx wrangler secret put INBOUND_SECRET`) ; règle **Catch-all** → *Send to a Worker* → `africansbiz-email`.
4. Si tu utilises déjà des boîtes LWS (ex. contact@africansbiz.com), prends un sous-domaine pour les adresses jetables (`mail.africansbiz.com`) et mets-le dans `MAIL_DOMAINS` et dans `CONFIG.MAIL_DOMAINS` du site.

### 4. Mettre le site en ligne
1. Dans `site/index.html`, remplis `CONFIG` : `SUPABASE_URL`, `SUPABASE_ANON_KEY`.
2. Héberge `site/` sur **Cloudflare Pages** (gratuit, relié au domaine) ou dans `public_html` de ton hébergement LWS.
3. Publicités : remplace le contenu des blocs `<div class="pub" …>` par le code de ta régie. Ils sont cachés pour les membres VIP.

### 5. L'application Android
1. Mets ce dossier dans un dépôt GitHub (depuis Termux : `git init`, `git add .`, `git commit`, `git push`).
2. GitHub → **Settings → Secrets and variables → Actions** : ajoute `SUPABASE_URL` et `SUPABASE_ANON_KEY` (sans eux, l'app est en mode démo).
3. Chaque push sur `main` compile l'APK et le publie dans **Releases** (`AfricansBiz 1.0.N`). Lien de téléchargement direct à mettre sur le site.
4. Android 7.0 minimum. Thème clair/sombre automatique.
5. Pour le Play Store : crée ta propre clé de signature et remplace `signingConfigs.getByName("debug")` dans `android/app/build.gradle.kts`.

### 6. Numéros SMS : retirés
Les numéros SMS ont été retirés du projet : au Bénin, chaque SIM est enregistrée à l'identité de son propriétaire, qui serait tenu responsable des abus. La fonction `inbound-sms` répond désormais « 410 Service retiré ».

## Modifier le catalogue et les prix
Directement dans Supabase → **Table Editor → services** (prix, description, délai, questions du formulaire, `actif` pour masquer). Le site et l'application se mettent à jour tout seuls.

## Sécurité
- Toutes les tables sont fermées au navigateur : seules les fonctions serveur y accèdent, après avoir vérifié que l'utilisateur est le client de la commande ou un administrateur.
- Un client ne voit jamais la commande d'un autre (réponse « introuvable »).
- Clé SasPay uniquement sur le serveur ; webhooks signés (HMAC + horodatage) ; chaque paiement est revérifié chez SasPay ; clé d'idempotence contre les doubles débits.
- Limites anti-abus : 15 commandes / jour / client, 20 messages / minute, 20 adresses mail / heure / IP.
- L'application n'accepte que des connexions HTTPS.

## Couleurs (design system)
Indigo `#23306E` (pagnes teints) · laiton `#B8862B` (bronzes d'Abomey) · ivoire `#F7F3EA` (coton) · palme `#2E6B4E` (livré) · latérite `#B3402A` (erreur). Toutes les couleurs du site sont des variables CSS en haut de `index.html` (`:root` et `[data-theme="dark"]`) ; celles de l'application sont dans `android/app/src/main/java/com/africansbiz/app/Theme.kt`.
