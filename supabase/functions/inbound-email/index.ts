// =========================================================
// Edge Function "inbound-email"
// Reçoit les mails bruts transmis par le Worker Cloudflare
// (en-têtes x-secret, x-to, x-from ; corps = mail brut RFC 822)
// Déploiement : supabase functions deploy inbound-email --no-verify-jwt
// =========================================================
import PostalMime from "npm:postal-mime@2";
import { admin, cors, err, json, safeEqual } from "../_shared/util.ts";

const MAX_BYTES = 1_000_000;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return err("Méthode non autorisée", 405);
  if (!safeEqual(req.headers.get("x-secret") ?? "", Deno.env.get("INBOUND_SECRET") ?? "")) return err("Interdit", 403);

  const to = (req.headers.get("x-to") ?? "").toLowerCase().trim();
  const { data: mb } = await admin.from("mailboxes").select("id, expires_at").eq("address", to).maybeSingle();
  if (!mb || new Date(mb.expires_at) < new Date()) return err("Adresse inconnue", 404);

  const raw = new Uint8Array(await req.arrayBuffer());
  if (raw.byteLength > MAX_BYTES) return err("Mail trop volumineux", 413);

  const mail = await PostalMime.parse(raw);
  const from = mail.from ? (mail.from.name ? `${mail.from.name} <${mail.from.address}>` : mail.from.address) : (req.headers.get("x-from") ?? "");

  // Le HTML est affiché dans une iframe "sandbox" côté site (pas de scripts),
  // on retire quand même les balises dangereuses par prudence.
  const html = (mail.html ?? "")
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<(iframe|object|embed|form)[\s\S]*?>/gi, "")
    .replace(/\son\w+\s*=\s*(".*?"|'.*?'|[^\s>]+)/gi, "");

  const { error } = await admin.from("emails").insert({
    mailbox_id: mb.id,
    from_addr: from?.slice(0, 300),
    subject: (mail.subject ?? "").slice(0, 300),
    body_text: (mail.text ?? "").slice(0, 200_000),
    body_html: html.slice(0, 500_000) || null,
  });
  if (error) return err("Enregistrement impossible", 500);

  // Garde au maximum 50 mails par boîte
  const { data: old } = await admin.from("emails").select("id").eq("mailbox_id", mb.id)
    .order("received_at", { ascending: false }).range(50, 200);
  if (old?.length) await admin.from("emails").delete().in("id", old.map((o) => o.id));

  return json({ ok: true });
});
