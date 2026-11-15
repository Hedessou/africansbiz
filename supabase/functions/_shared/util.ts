// Outils communs aux Edge Functions AfricansBiz
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

export const cors = {
  "Access-Control-Allow-Origin": Deno.env.get("SITE_ORIGIN") ?? "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-secret, x-to, x-from",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, "content-type": "application/json; charset=utf-8" },
  });
}

export const err = (message: string, status = 400) => json({ error: message }, status);

// Client "administrateur" : contourne RLS. Ne JAMAIS l'exposer au navigateur.
export const admin: SupabaseClient = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

// Retourne l'utilisateur connecté (ou null si visiteur anonyme)
export async function getUser(req: Request) {
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token || token.split(".").length !== 3) return null;
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data?.user) return null;
  return data.user;
}

export async function sha256(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomToken(bytes = 24): string {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...a)).replace(/[+/=]/g, "").slice(0, bytes);
}

// Comparaison en temps constant pour les secrets partagés
export function safeEqual(a: string, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// Secret partagé avec le Worker e-mail Cloudflare et le relais SMS Termux.
// Variable d'environnement INBOUND_SECRET si elle existe, sinon table privée « reglages » (mise en cache).
let inboundCache: string | null = null;
export async function inboundSecret(): Promise<string> {
  const env = Deno.env.get("INBOUND_SECRET");
  if (env) return env;
  if (inboundCache) return inboundCache;
  const { data } = await admin.from("reglages").select("valeur").eq("cle", "inbound_secret").maybeSingle();
  inboundCache = data?.valeur ?? "";
  return inboundCache;
}

export function clientIp(req: Request): string {
  return (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "inconnu";
}

export const PACKS: Record<string, { price: number; label: string }> = {
  bronze: { price: 500, label: "Bronze — 7 jours" },
  argent: { price: 2000, label: "Argent — 30 jours" },
  or: { price: 5000, label: "Or — 30 jours" },
};

// Espace annonceurs : bannière texte affichée aux visiteurs non VIP (site et app)
export const ANNONCE_FORMULES: Record<string, { label: string; jours: number; prix: number }> = {
  semaine: { label: "1 semaine", jours: 7, prix: 2500 },
  mois: { label: "1 mois", jours: 30, prix: 8000 },
};

// Durée de vie d'une boîte mail selon le pack (en heures)
export const MAIL_HOURS: Record<string, number> = { free: 1, bronze: 24, argent: 168, or: 168 };

// ---------- SasPay (https://docs.saspay.me) ----------
// Clé secrète sk_test_… (test) ou sk_live_… (production), JAMAIS côté navigateur ou app.
export const SASPAY_BASE = "https://api.saspay.me/api/v1";

export class SasPayError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export async function saspay(path: string, init: RequestInit & { idempotencyKey?: string } = {}) {
  const headers: Record<string, string> = {
    authorization: "Bearer " + Deno.env.get("SASPAY_SECRET_KEY"),
    "content-type": "application/json",
  };
  if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
  const r = await fetch(SASPAY_BASE + path, { ...init, headers: { ...headers, ...(init.headers ?? {}) } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j?.success === false) {
    const e = j?.error ?? j;
    throw new SasPayError(r.status, String(e?.code ?? "erreur"), String(e?.message ?? JSON.stringify(e).slice(0, 300)));
  }
  // Certaines routes renvoient { success, data, code }, d'autres l'objet directement
  return j?.data !== undefined && j?.success !== undefined ? j.data : j;
}

// Vérifie la signature d'un webhook SasPay : HMAC_SHA256(secret, timestamp + "." + corps brut), en hexadécimal
export async function verifySasPaySignature(rawBody: string, timestamp: string, signature: string): Promise<boolean> {
  const secret = Deno.env.get("SASPAY_WEBHOOK_SECRET") ?? "";
  if (!secret || !timestamp || !signature) return false;
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > 300) return false; // > 5 min : rejeté
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(timestamp + "." + rawBody));
  const hex = [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return safeEqual(hex, signature.toLowerCase());
}

// Indicatifs téléphoniques des pays où l'on encaisse
export const DIAL: Record<string, string> = {
  BJ: "229", TG: "228", CI: "225", SN: "221", BF: "226", ML: "223", NE: "227", CM: "237", GN: "224", NG: "234",
};

// Messages clairs pour les erreurs SasPay les plus courantes
export const PAY_ERRORS: Record<string, string> = {
  invalid_customer: "Informations client invalides (nom ou numéro).",
  invalid_method: "Ce moyen de paiement n'est pas disponible.",
  missing_method: "Choisis ton réseau Mobile Money.",
  no_route_available: "Ce réseau est momentanément indisponible, essaie un autre réseau.",
  invalid_country: "Pays non pris en charge.",
  prepayment_otp_missing: "Ce réseau demande un code préalable : compose le code USSD de ton opérateur puis réessaie.",
  merchant_suspended: "Paiements momentanément indisponibles.",
};

// Liste des réseaux Mobile Money actifs, par pays (mise en cache 1 h)
let netCache: { at: number; list: { code: string; name: string; country: string }[] } | null = null;
export async function payNetworks() {
  if (netCache && Date.now() - netCache.at < 3600e3) return netCache.list;
  const countries: any[] = await saspay("/countries/");
  const iso: Record<string, string> = {};
  for (const c of Array.isArray(countries) ? countries : countries?.results ?? []) iso[c.id] = c.iso_code;
  const list: { code: string; name: string; country: string }[] = [];
  for (let page = 1; page <= 5; page++) {
    const r: any = await saspay(`/networks/?page=${page}&page_size=100`);
    const items: any[] = Array.isArray(r) ? r : r?.results ?? [];
    for (const n of items) if (n.is_active !== false && iso[n.country]) list.push({ code: n.code, name: n.name, country: iso[n.country] });
    if (Array.isArray(r) || !r?.next) break;
  }
  const allowed = (Deno.env.get("PAY_COUNTRIES") ?? "BJ").split(",").map((s) => s.trim().toUpperCase());
  netCache = { at: Date.now(), list: list.filter((n) => allowed.includes(n.country)) };
  return netCache.list;
}

// Revérifie le paiement chez SasPay (jamais sur la seule parole du client ou du webhook)
// et active le pack si le statut est SUCCESS. Retourne le statut SasPay.
export async function checkAndApply(saspayId: string): Promise<string> {
  const r = await saspay(`/payments/${encodeURIComponent(saspayId)}/verify/`);
  const status = String(r?.status ?? "PENDING").toUpperCase();
  if (status === "SUCCESS") {
    const { error } = await admin.rpc("apply_payment", { p_tx: saspayId });
    if (error) throw error;
  } else if (status === "FAILED" || status === "CANCELLED") {
    await admin.from("payments").update({ status: "failed" }).eq("provider_tx", saspayId).eq("status", "pending");
  }
  return status;
}
