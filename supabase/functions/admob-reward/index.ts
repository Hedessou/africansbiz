// =========================================================
// AfricansBiz — admob-reward
// Vérification côté serveur (SSV) des pubs vidéo récompensées AdMob.
// Google appelle cette URL après chaque vidéo regardée jusqu'au bout ;
// on vérifie sa signature ECDSA avant de prolonger de 24 h la boîte mail temporaire
// dont l'adresse est passée dans custom_data (3 fois par jour et par adresse).
//
// URL à coller dans AdMob (bloc d'annonces avec récompense → Vérification côté serveur) :
//   https://<projet>.supabase.co/functions/v1/admob-reward
// Secret facultatif : ADMOB_REWARD_UNIT = numéro du bloc d'annonces (refuse les autres blocs)
// Déploiement : supabase functions deploy admob-reward --no-verify-jwt
// =========================================================
import { admin, json } from "../_shared/util.ts";

const KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
let cache: { at: number; keys: Map<string, CryptoKey> } | null = null;

function b64(s: string): Uint8Array {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

async function verifierKeys(force = false): Promise<Map<string, CryptoKey>> {
  if (!force && cache && Date.now() - cache.at < 3_600_000) return cache.keys;
  const j = await (await fetch(KEYS_URL)).json();
  const keys = new Map<string, CryptoKey>();
  for (const k of j.keys ?? []) {
    keys.set(String(k.keyId), await crypto.subtle.importKey(
      "spki", b64(k.base64), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"],
    ));
  }
  cache = { at: Date.now(), keys };
  return keys;
}

// Signature DER (SEQUENCE de deux INTEGER) → format brut r||s attendu par WebCrypto
function derToRaw(der: Uint8Array): Uint8Array {
  if (der[0] !== 0x30) throw new Error("signature DER invalide");
  let i = der[1] & 0x80 ? 2 + (der[1] & 0x7f) : 2;
  const entier = () => {
    if (der[i++] !== 0x02) throw new Error("signature DER invalide");
    const len = der[i++];
    let v = der.slice(i, i + len);
    i += len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    const out = new Uint8Array(32);
    out.set(v, 32 - v.length);
    return out;
  };
  const raw = new Uint8Array(64);
  raw.set(entier(), 0);
  raw.set(entier(), 32);
  return raw;
}

const texte = (t: string, status = 200) => new Response(t, { status });

Deno.serve(async (req) => {
  const url = new URL(req.url);
  const query = url.search.slice(1);
  const at = query.indexOf("&signature=");
  // AdMob teste l'URL sans paramètres lors de la configuration : on répond 200
  if (at < 0) return texte("ok");

  const message = query.slice(0, at);
  const signature = url.searchParams.get("signature") ?? "";
  const keyId = url.searchParams.get("key_id") ?? "";

  let key = (await verifierKeys()).get(keyId);
  if (!key) key = (await verifierKeys(true)).get(keyId); // Google a pu changer ses clés
  if (!key) return texte("clé inconnue", 400);

  let ok = false;
  try {
    ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, derToRaw(b64(signature)), new TextEncoder().encode(message));
  } catch { ok = false; }
  if (!ok) return texte("signature invalide", 403);

  const unit = Deno.env.get("ADMOB_REWARD_UNIT");
  if (unit && url.searchParams.get("ad_unit") !== unit) return texte("bloc d'annonces refusé", 403);

  const tx = url.searchParams.get("transaction_id") ?? "";
  const adresse = (url.searchParams.get("custom_data") ?? "").trim().toLowerCase();
  const user = url.searchParams.get("user_id") ?? "";
  // Les tests AdMob n'ont pas d'adresse : on confirme sans rien accorder
  if (!tx || !/^[^@\s]+@[^@\s]+$/.test(adresse)) return texte("ok");

  const { data, error } = await admin.rpc("accorder_recompense", {
    p_tx: "admob:" + tx, p_adresse: adresse, p_user: /^[0-9a-f-]{36}$/.test(user) ? user : null,
  });
  if (error) {
    console.error(error);
    return texte("erreur", 500);
  }
  return json({ expires_at: data });
});
