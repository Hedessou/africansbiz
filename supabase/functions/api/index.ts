// =========================================================
// AfricansBiz — Edge Function "api" (site web ET application Android)
//
// Public
//   GET  /services                 catalogue
//   GET  /pay/networks             réseaux Mobile Money disponibles
//   POST /mailbox                  créer une boîte mail temporaire
//   GET  /mailbox/messages         lire les mails (address + token)
//   GET  /annonces/formules        tarifs de l'espace annonceurs
//   GET  /annonces/servir          une annonce active au hasard (compte une vue)
//   GET  /annonces/clic/:id        compte le clic puis redirige vers l'annonceur
// Connecté
//   GET  /me                       profil (plan, rôle…)
//   POST /orders                   passer commande
//   GET  /orders[?all=1]           mes commandes (toutes si admin)
//   GET  /orders/:id               détail + historique
//   GET  /orders/:id/messages      chat
//   POST /orders/:id/messages      écrire dans le chat
//   POST /orders/:id/pay           payer la commande (SasPay, push direct)
//   POST /orders/:id/accept        le client valide la livraison
//   POST /pay/init                 acheter un pack VIP
//   POST /pay/otp                  code OTP après push (certains réseaux)
//   GET  /pay/status?id=           suivi d'un paiement
//   POST /annonces                 créer une annonce {formule, titre, texte, lien}
//   GET  /annonces[?all=1]         mes annonces (toutes si admin)
//   POST /annonces/:id/payer       payer l'annonce (SasPay)
// Administrateur
//   POST /orders/:id/quote         envoyer un devis {amount, note}
//   POST /orders/:id/status        changer d'étape {status, note}
//   POST /orders/:id/deliver       livrer {label, url}
//   POST /annonces/:id/revue       {decision: "accepter" | "refuser", motif}
//
// Déploiement : supabase functions deploy api --no-verify-jwt
// =========================================================
import {
  admin, ANNONCE_FORMULES, checkAndApply, clientIp, cors, DIAL, err, getUser, json, MAIL_HOURS, PACKS, PAY_ERRORS, payNetworks,
  randomToken, saspay, SasPayError, sha256,
} from "../_shared/util.ts";

const DOMAINS = (Deno.env.get("MAIL_DOMAINS") ?? "africansbiz.com").split(",").map((d) => d.trim().toLowerCase());
const MAILBOXES_PER_IP_PER_HOUR = 20;
const ORDERS_PER_USER_PER_DAY = 15;
const MESSAGES_PER_MINUTE = 20;
const WORDS = ["kofi", "ama", "sena", "yao", "afi", "koku", "edem", "akpe", "fifa", "kossi", "abla", "mawu", "dodji", "eyram"];
const STATUS_LABEL: Record<string, string> = {
  devis: "Devis en préparation", a_payer: "À payer", payee: "Payée, démarrage", en_cours: "Réalisation démarrée",
  validation: "Livraison prête, à valider", livree: "Livrée", annulee: "Commande annulée",
};
const UUID = "[0-9a-f-]{36}";
const SITE = Deno.env.get("SITE_ORIGIN") ?? "https://hedessou.github.io/africansbiz/";
const API_PUBLIC = (Deno.env.get("SUPABASE_URL") ?? "") + "/functions/v1/api";

async function profileOf(userId: string) {
  const { data } = await admin.from("profiles").select("*").eq("id", userId).maybeSingle();
  const p = data ?? { plan: "free", credits: 0, plan_until: null, banned: false, role: "client" };
  const expired = p.plan !== "free" && p.plan_until && new Date(p.plan_until) < new Date();
  return expired ? { ...p, plan: "free" } : p; // les crédits restants (vidéos, ancien pack) restent utilisables
}

// Charge une commande et vérifie que l'utilisateur y a droit (client ou admin)
async function loadOrder(id: string, userId: string, isAdmin: boolean) {
  const { data: o } = await admin.from("orders").select("*").eq("id", id).maybeSingle();
  if (!o) return null;
  if (!isAdmin && o.client !== userId) return null; // ne révèle pas l'existence de la commande
  return o;
}

// Met en forme une liste de commandes pour le site / l'app
async function shapeOrders(rows: any[], isAdmin: boolean) {
  const svcIds = [...new Set(rows.map((r) => r.service_id))];
  const { data: svcs } = await admin.from("services").select("id, nom").in("id", svcIds.length ? svcIds : ["-"]);
  const names: Record<string, string> = Object.fromEntries((svcs ?? []).map((s) => [s.id, s.nom]));
  let emails: Record<string, string> = {};
  if (isAdmin) {
    const ids = [...new Set(rows.map((r) => r.client))];
    const { data: profs } = await admin.from("profiles").select("id, email").in("id", ids.length ? ids : ["00000000-0000-0000-0000-000000000000"]);
    emails = Object.fromEntries((profs ?? []).map((p) => [p.id, p.email]));
  }
  return rows.map((o) => ({
    id: o.id, ref: o.ref, service_id: o.service_id, service_name: names[o.service_id] ?? o.service_id,
    brief: o.brief, answers: o.answers, amount: o.amount, status: o.status,
    created_at: o.created_at, updated_at: o.updated_at,
    unread: isAdmin ? o.admin_unread : o.client_unread,
    client_email: isAdmin ? emails[o.client] ?? null : undefined,
  }));
}

async function addEvent(orderId: string, kind: string, label: string, detail: string | null, by: string | null) {
  await admin.from("order_events").insert({ order_id: orderId, kind, label, detail, created_by: by });
  await admin.from("orders").update({ updated_at: new Date().toISOString() }).eq("id", orderId);
}

// Lance un paiement SasPay "softpay" : la demande arrive directement sur le téléphone du client
async function pushPayment(
  user: { id: string; email?: string },
  o: { amount: number; description: string; pack?: string; order_id?: string; annonce_id?: string },
  body: any,
) {
  const country = String(body.country ?? "BJ").toUpperCase();
  const network = String(body.network ?? "");
  const nets = await payNetworks();
  if (!nets.some((n) => n.code === network && n.country === country)) return err("Réseau de paiement non disponible");
  const dial = DIAL[country];
  if (!dial) return err("Pays non pris en charge");
  let digits = String(body.phone ?? "").replace(/\D/g, "");
  if (digits.startsWith(dial)) digits = digits.slice(dial.length);
  if (digits.length < 8) return err("Numéro Mobile Money invalide");
  const [first, ...rest] = String(body.full_name ?? "").trim().split(/\s+/);

  const { data: pay, error: payErr } = await admin.from("payments")
    .insert({ user_id: user.id, pack: o.pack ?? null, order_id: o.order_id ?? null, annonce_id: o.annonce_id ?? null, amount: o.amount, provider: "saspay" })
    .select("id").single();
  if (payErr) return err("Paiement impossible, réessaie", 500);

  try {
    const r = await saspay("/payments/softpay/", {
      method: "POST",
      idempotencyKey: pay.id, // un double clic ne débite jamais deux fois
      body: JSON.stringify({
        amount: o.amount.toFixed(2),
        currency: "XOF",
        country,
        network,
        description: o.description,
        fee_charge_mode: Deno.env.get("SASPAY_FEE_MODE") || undefined,
        customer: {
          email: user.email,
          first_name: first || "Client",
          last_name: rest.join(" ") || "AfricansBiz",
          phone: "+" + dial + digits,
        },
        metadata: { payment_row: pay.id, user_id: user.id, pack: o.pack ?? null, order_id: o.order_id ?? null, annonce_id: o.annonce_id ?? null },
      }),
    });
    await admin.from("payments").update({ provider_tx: String(r.id) }).eq("id", pay.id);
    return json({
      payment_id: r.id,
      status: r.status,
      instructions: r.instructions ?? "Valide le paiement sur ton téléphone (notification ou menu Mobile Money).",
      otp_after_push: /coris/i.test(network),
      checkout_url: r.checkout_url || null, // le site refuse la redirection et propose un autre réseau
    });
  } catch (e) {
    await admin.from("payments").update({ status: "failed" }).eq("id", pay.id);
    if (e instanceof SasPayError) return err(PAY_ERRORS[e.code] ?? "Paiement refusé : " + e.message, 422);
    throw e;
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const url = new URL(req.url);
  const path = url.pathname.replace(/^.*\/api/, "") || "/";
  const user = await getUser(req);

  try {
    // =================== PUBLIC ===================
    if (req.method === "GET" && path === "/services") {
      const { data } = await admin.from("services").select("id, fam, nom, court, prix, type, unite, delai, q")
        .eq("actif", true).order("ordre");
      return json({ services: data ?? [] });
    }

    if (req.method === "GET" && path === "/pay/networks") {
      return json({ networks: await payNetworks() });
    }

    // ---------- Créer une boîte mail ----------
    if (req.method === "POST" && path === "/mailbox") {
      const body = await req.json().catch(() => ({}));
      const prof = user ? await profileOf(user.id) : { plan: "free", banned: false };
      if (prof.banned) return err("Compte bloqué", 403);

      const ipHash = await sha256(clientIp(req) + (Deno.env.get("IP_SALT") ?? ""));
      const since = new Date(Date.now() - 3600e3).toISOString();
      const { count } = await admin.from("mailboxes").select("id", { count: "exact", head: true })
        .eq("ip_hash", ipHash).gte("created_at", since);
      if ((count ?? 0) >= MAILBOXES_PER_IP_PER_HOUR) return err("Trop d'adresses créées, réessaie dans une heure", 429);

      const domain = DOMAINS.includes(String(body.domain ?? "").toLowerCase()) ? String(body.domain).toLowerCase() : DOMAINS[0];
      let local: string;
      if (body.name) {
        if (prof.plan === "free") return err("Adresse personnalisée réservée aux VIP", 403);
        local = String(body.name).toLowerCase();
        if (!/^[a-z0-9][a-z0-9._-]{2,29}$/.test(local)) return err("Nom invalide");
        if (/^(admin|postmaster|abuse|contact|support|noreply|no-reply|root|webmaster|security|bonjour)$/.test(local)) {
          return err("Ce nom est réservé");
        }
      } else {
        const n = crypto.getRandomValues(new Uint32Array(1))[0] % 10000;
        local = WORDS[Math.floor(Math.random() * WORDS.length)] + "." + String(n).padStart(4, "0");
      }
      const address = `${local}@${domain}`;
      const { data: existing } = await admin.from("mailboxes").select("id, expires_at").eq("address", address).maybeSingle();
      if (existing) {
        if (new Date(existing.expires_at) > new Date()) return err("Cette adresse est déjà utilisée", 409);
        await admin.from("mailboxes").delete().eq("id", existing.id);
      }
      const expires_at = new Date(Date.now() + MAIL_HOURS[prof.plan] * 3600e3).toISOString();
      const token = randomToken();
      const { error } = await admin.from("mailboxes").insert({ address, token, owner: user?.id ?? null, ip_hash: ipHash, expires_at });
      if (error) return err("Création impossible, réessaie", 500);
      return json({ address, token, expires_at });
    }

    if (req.method === "GET" && path === "/mailbox/messages") {
      const address = (url.searchParams.get("address") ?? "").toLowerCase();
      const token = url.searchParams.get("token") ?? "";
      const { data: mb } = await admin.from("mailboxes").select("id, token, expires_at").eq("address", address).maybeSingle();
      if (!mb || mb.token !== token) return err("Boîte introuvable", 404);
      if (new Date(mb.expires_at) < new Date()) return err("Boîte expirée", 404);
      const { data } = await admin.from("emails").select("id, from_addr, subject, body_text, body_html, received_at")
        .eq("mailbox_id", mb.id).order("received_at", { ascending: false }).limit(50);
      return json({ messages: data ?? [], expires_at: mb.expires_at });
    }

    let m: RegExpMatchArray | null = null;

    // ---------- Annonces (public) ----------
    if (req.method === "GET" && path === "/annonces/formules") return json({ formules: ANNONCE_FORMULES });

    if (req.method === "GET" && path === "/annonces/servir") {
      const { data } = await admin.from("annonces").select("id, titre, texte")
        .eq("statut", "active").gt("fin", new Date().toISOString()).limit(30);
      if (!data?.length) return json({ annonce: null });
      const a = data[Math.floor(Math.random() * data.length)];
      await admin.rpc("annonce_compter", { p_id: a.id, p_clic: false });
      return json({ annonce: { id: a.id, titre: a.titre, texte: a.texte, url: `${API_PUBLIC}/annonces/clic/${a.id}` } });
    }

    m = path.match(new RegExp(`^/annonces/clic/(${UUID})$`));
    if (req.method === "GET" && m) {
      const { data: a } = await admin.from("annonces").select("lien, statut").eq("id", m[1]).maybeSingle();
      if (!a || a.statut !== "active") return new Response(null, { status: 302, headers: { ...cors, location: SITE } });
      await admin.rpc("annonce_compter", { p_id: m[1], p_clic: true });
      return new Response(null, { status: 302, headers: { ...cors, location: a.lien } });
    }

    // =================== CONNECTÉ ===================
    if (!user) return err("Connecte-toi d'abord", 401);
    const prof = await profileOf(user.id);
    if (prof.banned) return err("Compte bloqué", 403);
    const isAdmin = prof.role === "admin";

    if (req.method === "GET" && path === "/me") {
      return json({ email: user.email, plan: prof.plan, plan_until: prof.plan_until, credits: prof.credits, role: prof.role ?? "client" });
    }

    // ---------- Passer commande ----------
    if (req.method === "POST" && path === "/orders") {
      const b = await req.json().catch(() => ({}));
      const { data: svc } = await admin.from("services").select("*").eq("id", String(b.service_id ?? "")).eq("actif", true).maybeSingle();
      if (!svc) return err("Service introuvable", 404);
      const brief = String(b.brief ?? "").trim();
      if (brief.length < 10 || brief.length > 5000) return err("Décris ton besoin (10 à 5000 caractères)");
      const since = new Date(Date.now() - 864e5).toISOString();
      const { count } = await admin.from("orders").select("id", { count: "exact", head: true }).eq("client", user.id).gte("created_at", since);
      if ((count ?? 0) >= ORDERS_PER_USER_PER_DAY) return err("Trop de commandes aujourd'hui, réessaie demain", 429);

      const answers: Record<string, string> = {};
      for (const [k, v] of Object.entries(b.answers ?? {}).slice(0, 12)) answers[String(k).slice(0, 80)] = String(v).slice(0, 500);
      const { data: ref } = await admin.rpc("next_order_ref");
      const fixe = svc.type === "fixe";
      const { data: o, error } = await admin.from("orders").insert({
        ref, client: user.id, service_id: svc.id, brief, answers,
        whatsapp: String(b.whatsapp ?? "").slice(0, 30) || null,
        amount: fixe ? svc.prix : null, status: fixe ? "a_payer" : "devis",
        admin_unread: 1,
      }).select("*").single();
      if (error) return err("Commande impossible, réessaie", 500);
      await addEvent(o.id, "status", "Commande reçue", fixe ? `Prix fixe : ${svc.prix} F` : "Devis gratuit sous 24 h", user.id);
      if (b.whatsapp) await admin.from("profiles").update({ whatsapp: String(b.whatsapp).slice(0, 30) }).eq("id", user.id);
      return json({ order: (await shapeOrders([o], false))[0] });
    }

    // ---------- Liste des commandes ----------
    if (req.method === "GET" && path === "/orders") {
      const all = url.searchParams.get("all") === "1" && isAdmin;
      let q = admin.from("orders").select("*").order("updated_at", { ascending: false }).limit(200);
      if (!all) q = q.eq("client", user.id);
      const { data } = await q;
      return json({ orders: await shapeOrders(data ?? [], all) });
    }

    // ---------- Routes d'une commande ----------
    m = path.match(new RegExp(`^/orders/(${UUID})(/[a-z]+)?$`));
    if (m) {
      const o = await loadOrder(m[1], user.id, isAdmin);
      if (!o) return err("Commande introuvable", 404);
      const action = m[2] ?? "";
      const asAdmin = isAdmin && o.client !== user.id; // un admin qui commande pour lui-même reste client
      const body = req.method === "POST" ? await req.json().catch(() => ({})) : {};

      if (req.method === "GET" && action === "") {
        const { data: events } = await admin.from("order_events").select("kind, label, detail, created_at")
          .eq("order_id", o.id).order("created_at");
        const [shaped] = await shapeOrders([o], isAdmin);
        return json({ order: { ...shaped, events: events ?? [] } });
      }

      // Chat : lecture (et remise à zéro des non-lus de ce côté)
      if (req.method === "GET" && action === "/messages") {
        let q = admin.from("messages").select("id, sender_role, body, created_at").eq("order_id", o.id).order("created_at").limit(500);
        const after = url.searchParams.get("after");
        if (after) q = q.gt("created_at", after);
        const { data } = await q;
        await admin.from("orders").update(asAdmin ? { admin_unread: 0 } : { client_unread: 0 }).eq("id", o.id);
        return json({ messages: (data ?? []).map((x) => ({ id: x.id, sender: x.sender_role, body: x.body, created_at: x.created_at })) });
      }

      // Chat : écriture
      if (req.method === "POST" && action === "/messages") {
        const text = String(body.body ?? "").trim();
        if (!text || text.length > 4000) return err("Message vide ou trop long");
        const since = new Date(Date.now() - 60e3).toISOString();
        const { count } = await admin.from("messages").select("id", { count: "exact", head: true }).eq("sender", user.id).gte("created_at", since);
        if ((count ?? 0) >= MESSAGES_PER_MINUTE) return err("Trop de messages, patiente une minute", 429);
        const role = asAdmin ? "admin" : "client";
        const { data: msg, error } = await admin.from("messages").insert({ order_id: o.id, sender: user.id, sender_role: role, body: text })
          .select("id, sender_role, body, created_at").single();
        if (error) return err("Envoi impossible", 500);
        await admin.from("orders").update(asAdmin
          ? { client_unread: o.client_unread + 1, updated_at: new Date().toISOString() }
          : { admin_unread: o.admin_unread + 1, updated_at: new Date().toISOString() }).eq("id", o.id);
        return json({ message: { id: msg.id, sender: msg.sender_role, body: msg.body, created_at: msg.created_at } });
      }

      // Paiement de la commande
      if (req.method === "POST" && action === "/pay") {
        if (o.client !== user.id) return err("Seul le client peut payer sa commande", 403);
        if (o.status !== "a_payer" || !o.amount) return err("Cette commande n'est pas à payer");
        return await pushPayment(user, { amount: o.amount, description: `AfricansBiz — ${o.ref}`, order_id: o.id }, body);
      }

      // Le client valide la livraison
      if (req.method === "POST" && action === "/accept") {
        if (o.client !== user.id) return err("Seul le client peut valider", 403);
        if (o.status !== "validation") return err("Rien à valider pour l'instant");
        await admin.from("orders").update({ status: "livree", admin_unread: o.admin_unread + 1 }).eq("id", o.id);
        await addEvent(o.id, "status", "Livraison validée par le client", null, user.id);
        return json({ ok: true });
      }

      // ----- Actions administrateur -----
      if (["/quote", "/status", "/deliver"].includes(action)) {
        if (!isAdmin) return err("Réservé à l'administrateur", 403);

        if (action === "/quote") {
          const amount = Math.round(Number(body.amount));
          if (!Number.isFinite(amount) || amount < 100 || amount > 50_000_000) return err("Montant invalide");
          if (!["devis", "a_payer"].includes(o.status)) return err("Le devis ne peut plus être modifié");
          await admin.from("orders").update({ amount, status: "a_payer", client_unread: o.client_unread + 1 }).eq("id", o.id);
          await addEvent(o.id, "status", `Devis envoyé : ${amount.toLocaleString("fr-FR")} F`, String(body.note ?? "").slice(0, 1000) || null, user.id);
          return json({ ok: true });
        }

        if (action === "/status") {
          const status = String(body.status ?? "");
          if (!STATUS_LABEL[status]) return err("Étape inconnue");
          await admin.from("orders").update({ status, client_unread: o.client_unread + 1 }).eq("id", o.id);
          await addEvent(o.id, "status", STATUS_LABEL[status], String(body.note ?? "").slice(0, 1000) || null, user.id);
          return json({ ok: true });
        }

        if (action === "/deliver") {
          const label = String(body.label ?? "").trim().slice(0, 120), link = String(body.url ?? "").trim();
          if (!label || !/^https:\/\/[^\s]+$/.test(link)) return err("Nom et lien https requis");
          await admin.from("orders").update({ status: "validation", client_unread: o.client_unread + 1 }).eq("id", o.id);
          await addEvent(o.id, "delivery", label, link.slice(0, 1000), user.id);
          return json({ ok: true });
        }
      }
      return err("Route inconnue", 404);
    }

    // ---------- Espace annonceurs ----------
    if (req.method === "POST" && path === "/annonces") {
      const b = await req.json().catch(() => ({}));
      const f = ANNONCE_FORMULES[String(b.formule ?? "")];
      if (!f) return err("Formule inconnue");
      const titre = String(b.titre ?? "").trim(), texte = String(b.texte ?? "").trim(), lien = String(b.lien ?? "").trim();
      if (titre.length < 3 || titre.length > 60) return err("Titre : 3 à 60 caractères");
      if (texte.length < 10 || texte.length > 160) return err("Texte : 10 à 160 caractères");
      if (!/^https:\/\/[^\s]+$/.test(lien) || lien.length > 300) return err("Lien https:// valide requis");
      const { count } = await admin.from("annonces").select("id", { count: "exact", head: true })
        .eq("user_id", user.id).eq("statut", "a_payer");
      if ((count ?? 0) >= 5) return err("Tu as déjà 5 annonces en attente de paiement", 429);
      const { data, error } = await admin.from("annonces")
        .insert({ user_id: user.id, titre, texte, lien, formule: b.formule, jours: f.jours, prix: f.prix }).select().single();
      if (error) return err("Création impossible, réessaie", 500);
      return json({ annonce: data });
    }

    if (req.method === "GET" && path === "/annonces") {
      let q = admin.from("annonces").select("*").order("created_at", { ascending: false }).limit(100);
      if (!(isAdmin && url.searchParams.get("all") === "1")) q = q.eq("user_id", user.id);
      const { data } = await q;
      return json({ annonces: data ?? [] });
    }

    m = path.match(new RegExp(`^/annonces/(${UUID})/(payer|revue)$`));
    if (req.method === "POST" && m) {
      const { data: a } = await admin.from("annonces").select("*").eq("id", m[1]).maybeSingle();
      if (!a || (a.user_id !== user.id && !isAdmin)) return err("Annonce introuvable", 404);
      const body = await req.json().catch(() => ({}));
      if (m[2] === "payer") {
        if (a.user_id !== user.id) return err("Seul l'annonceur peut payer son annonce", 403);
        if (a.statut !== "a_payer") return err("Cette annonce n'est pas à payer");
        return await pushPayment(user, { amount: a.prix, description: `AfricansBiz — Annonce ${a.formule}`, annonce_id: a.id }, body);
      }
      if (!isAdmin) return err("Réservé à l'administrateur", 403);
      if (a.statut !== "en_revue") return err("Rien à valider pour cette annonce");
      if (body.decision === "accepter") {
        const debut = new Date(), fin = new Date(debut.getTime() + a.jours * 86_400_000);
        await admin.from("annonces").update({ statut: "active", debut: debut.toISOString(), fin: fin.toISOString(), motif: null }).eq("id", a.id);
      } else {
        await admin.from("annonces").update({ statut: "refusee", motif: String(body.motif ?? "").slice(0, 300) || "Annonce refusée" }).eq("id", a.id);
      }
      return json({ ok: true });
    }

    // ---------- Paiement d'un pack VIP ----------
    if (req.method === "POST" && path === "/pay/init") {
      const body = await req.json().catch(() => ({}));
      const p = PACKS[body.pack];
      if (!p) return err("Pack inconnu");
      return await pushPayment(user, { amount: p.price, description: `AfricansBiz — Pack ${p.label}`, pack: body.pack }, body);
    }

    if (req.method === "POST" && path === "/pay/otp") {
      const { payment_id, otp } = await req.json().catch(() => ({}));
      const { data: pay } = await admin.from("payments").select("id").eq("provider_tx", String(payment_id)).eq("user_id", user.id).maybeSingle();
      if (!pay) return err("Paiement introuvable", 404);
      try {
        const r = await saspay(`/payments/${encodeURIComponent(payment_id)}/confirm-otp/`, { method: "POST", body: JSON.stringify({ otp: String(otp ?? "") }) });
        return json({ status: r.status });
      } catch (e) {
        if (e instanceof SasPayError) return err("Code refusé. Vérifie le code ou réessaie.", 422);
        throw e;
      }
    }

    if (req.method === "GET" && path === "/pay/status") {
      const id = url.searchParams.get("id") ?? "";
      const { data: pay } = await admin.from("payments").select("id, status").eq("provider_tx", id).eq("user_id", user.id).maybeSingle();
      if (!pay) return err("Paiement introuvable", 404);
      if (pay.status === "approved") return json({ status: "SUCCESS" });
      return json({ status: await checkAndApply(id) });
    }

    return err("Route inconnue", 404);
  } catch (e) {
    console.error(e);
    return err("Erreur serveur", 500);
  }
});
