// =========================================================
// Edge Function "pay-webhook" : notifications SasPay
// Active le pack VIP quand SasPay annonce transaction.success.
// 1. Vérifie la signature (X-Webhook-Signature / X-Webhook-Timestamp)
// 2. Revérifie quand même le paiement auprès de l'API SasPay
// Déploiement : supabase functions deploy pay-webhook --no-verify-jwt
// Dans SasPay > Webhooks, ajoute l'URL :
//   https://<projet>.supabase.co/functions/v1/pay-webhook
// et abonne-la à transaction.success et transaction.failed.
// =========================================================
import { admin, checkAndApply, err, json, verifySasPaySignature } from "../_shared/util.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return err("Méthode non autorisée", 405);

  const raw = await req.text(); // corps BRUT : indispensable pour la signature
  const ok = await verifySasPaySignature(
    raw,
    req.headers.get("x-webhook-timestamp") ?? "",
    req.headers.get("x-webhook-signature") ?? "",
  );
  if (!ok) return err("Signature invalide", 403);

  let body: any;
  try { body = JSON.parse(raw); } catch { return err("Corps invalide"); }

  const event = String(body?.event ?? req.headers.get("x-webhook-event") ?? "");
  if (event === "webhook.test") return json({ ok: true });
  if (!event.startsWith("transaction.")) return json({ ok: true, ignored: true });

  const id = String(body?.data?.id ?? "");
  if (!id) return err("Identifiant manquant");

  // On ne traite que nos propres paiements
  const { data: pay } = await admin.from("payments").select("id").eq("provider_tx", id).maybeSingle();
  if (!pay) return json({ ok: true, ignored: true });

  try {
    const status = await checkAndApply(id);
    return json({ ok: true, status });
  } catch (e) {
    console.error(e);
    return err("Vérification impossible", 500); // SasPay réessaiera
  }
});
