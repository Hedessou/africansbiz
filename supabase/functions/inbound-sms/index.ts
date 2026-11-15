// =========================================================
// Edge Function "inbound-sms"
// Reçoit les SMS envoyés par les téléphones relais (script Termux)
// ou par un fournisseur de numéros (webhook).
//   POST { to, from, body, ref?, received_at? }  -> enregistre un SMS
//        (ref = identifiant unique côté relais, évite les doublons)
//   POST { to, heartbeat: true }                 -> "je suis en ligne"
// En-tête obligatoire : x-secret = INBOUND_SECRET
// Déploiement : supabase functions deploy inbound-sms --no-verify-jwt
// =========================================================
import { admin, cors, err, json, safeEqual, sha256 } from "../_shared/util.ts";

const normalize = (n: string) => "+" + String(n ?? "").replace(/\D/g, "");

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return err("Méthode non autorisée", 405);
  if (!safeEqual(req.headers.get("x-secret") ?? "", Deno.env.get("INBOUND_SECRET") ?? "")) return err("Interdit", 403);

  const p = await req.json().catch(() => null);
  if (!p?.to) return err("Champ 'to' manquant");

  const e164 = normalize(p.to);
  const { data: n } = await admin.from("numbers").select("id, rented_by, rented_until, active").eq("e164", e164).maybeSingle();
  if (!n) return err("Numéro non enregistré : ajoute-le dans la table numbers", 404);

  await admin.from("numbers").update({ last_seen_at: new Date().toISOString() }).eq("id", n.id);
  if (p.heartbeat) return json({ ok: true });
  if (!n.active || !p.body) return json({ ok: true, ignored: true });

  const receivedAt = p.received_at ? new Date(p.received_at) : new Date();
  const when = isNaN(receivedAt.getTime()) ? new Date() : receivedAt;
  const rented = n.rented_by && n.rented_until && new Date(n.rented_until) > when;

  const { error } = await admin.from("sms").insert({
    number_id: n.id,
    sender: String(p.from ?? "Inconnu").slice(0, 60),
    body: String(p.body).slice(0, 2000),
    owner: rented ? n.rented_by : null,
    dedupe: await sha256(e164 + "|" + (p.ref ?? `${p.from ?? ""}|${p.body}|${p.received_at ?? ""}`)),
    received_at: when.toISOString(),
  });
  // 23505 = doublon déjà reçu : ce n'est pas une erreur
  if (error && error.code !== "23505") return err("Enregistrement impossible", 500);
  return json({ ok: true, private: !!rented });
});
