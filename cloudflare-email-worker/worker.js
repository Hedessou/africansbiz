// =========================================================
// Worker Cloudflare "Email" : reçoit TOUS les mails du domaine
// (règle catch-all d'Email Routing) et les transmet à Supabase.
// Variables à définir dans Cloudflare (Settings > Variables) :
//   INBOUND_URL    = https://<projet>.supabase.co/functions/v1/inbound-email
//   INBOUND_SECRET = le même secret que dans Supabase (secret, pas en clair)
// =========================================================
export default {
  async email(message, env) {
    if (message.rawSize > 1_000_000) {
      message.setReject("Message trop volumineux");
      return;
    }
    const raw = await new Response(message.raw).arrayBuffer();
    const r = await fetch(env.INBOUND_URL, {
      method: "POST",
      headers: {
        "content-type": "message/rfc822",
        "x-secret": env.INBOUND_SECRET,
        "x-to": message.to,
        "x-from": message.from,
      },
      body: raw,
    });
    if (r.status === 404) message.setReject("Adresse inconnue ou expirée");
    else if (!r.ok) throw new Error("Supabase a répondu " + r.status); // Cloudflare réessaiera
  },
};
