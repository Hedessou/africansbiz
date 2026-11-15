package com.africansbiz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

// =========================================================
// Modèles (identiques à ceux du site web)
// =========================================================
data class Question(val k: String, val label: String, val ph: String)
data class Service(
    val id: String, val fam: String, val nom: String, val court: String, val prix: Int,
    val type: String, val unite: String?, val delai: String, val questions: List<Question>,
) { val fixe get() = type == "fixe" }

data class OrderEvent(val kind: String, val label: String, val detail: String?, val createdAt: String)
data class Order(
    val id: String, val ref: String, val serviceId: String, val serviceName: String, val brief: String,
    val answers: Map<String, String>, val amount: Int?, val status: String, val createdAt: String,
    val unread: Int, val clientEmail: String?, val events: List<OrderEvent> = emptyList(),
)
data class ChatMessage(val id: String, val sender: String, val body: String, val createdAt: String)
data class PayNetwork(val code: String, val name: String)
data class PayStart(val paymentId: String, val instructions: String, val otpAfterPush: Boolean, val checkoutUrl: String?)
data class Me(val email: String?, val plan: String, val credits: Int, val role: String)
data class Mailbox(val address: String, val token: String, val expiresAt: String, val createdMs: Long)
data class Mail(val id: String, val from: String, val subject: String, val html: String, val text: String, val receivedAt: String)
data class Phone(val id: String, val e164: String, val country: String, val sms24h: Int, val lastSmsAt: String?)
data class Sms(val sender: String, val body: String, val receivedAt: String)
data class Session(val access: String, val refresh: String, val expiresAt: Long, val email: String)

class ApiException(message: String) : Exception(message)

val ETAPES = listOf("Commande", "Devis", "Paiement", "Réalisation", "Validation", "Livrée")
val STATUTS = linkedMapOf(
    "devis" to "Devis en préparation", "a_payer" to "À payer", "payee" to "Payée, démarrage",
    "en_cours" to "En réalisation", "validation" to "À valider", "livree" to "Livrée", "annulee" to "Annulée",
)
fun rangStatut(s: String) = when (s) {
    "devis" -> 1; "a_payer" -> 2; "payee", "en_cours" -> 3; "validation" -> 4; "livree" -> 6; else -> 0
}
val FAMILLES = linkedMapOf(
    "creer" to "Créer", "automatiser" to "Automatiser", "gerer" to "Gérer", "installer" to "Installer", "rediger" to "Rédiger",
)

// ---------- utilitaires de format ----------
fun francs(n: Int?): String =
    if (n == null) "—" else NumberFormat.getIntegerInstance(Locale.FRANCE).format(n).replace(' ', ' ').replace(' ', ' ') + " F"

fun parseIso(s: String): Date? {
    // Supabase renvoie par ex. 2026-11-15T13:02:29.123456+00:00 : on retire les fractions de seconde
    val c = s.trim().replace(' ', 'T').replace(Regex("\\.\\d+"), "")
    for (f in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss")) {
        try {
            val df = SimpleDateFormat(f, Locale.US); df.timeZone = TimeZone.getTimeZone("UTC")
            return df.parse(c)
        } catch (_: Exception) { }
    }
    return null
}
fun nowIso(offsetMin: Long = 0): String {
    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US); df.timeZone = TimeZone.getTimeZone("UTC")
    return df.format(Date(System.currentTimeMillis() - offsetMin * 60_000))
}
fun ilYa(s: String): String {
    val d = parseIso(s) ?: return ""
    val sec = (System.currentTimeMillis() - d.time) / 1000
    return when {
        sec < 60 -> "à l'instant"
        sec < 3600 -> "il y a ${sec / 60} min"
        sec < 86400 -> "il y a ${sec / 3600} h"
        else -> SimpleDateFormat("d MMM", Locale.FRANCE).format(d)
    }
}
fun heure(s: String): String = parseIso(s)?.let { SimpleDateFormat("HH:mm", Locale.FRANCE).format(it) } ?: ""
fun dateHeure(s: String): String = parseIso(s)?.let { SimpleDateFormat("d MMM, HH:mm", Locale.FRANCE).format(it) } ?: ""

// =========================================================
// Contrat commun : vrai serveur ou démo
// =========================================================
interface Backend {
    val demo: Boolean
    suspend fun sendCode(email: String)
    suspend fun verifyCode(email: String, code: String): Session
    suspend fun me(): Me
    suspend fun services(): List<Service>
    suspend fun createOrder(serviceId: String, brief: String, answers: Map<String, String>, whatsapp: String): Order
    suspend fun orders(all: Boolean): List<Order>
    suspend fun order(id: String): Order
    suspend fun messages(id: String): List<ChatMessage>
    suspend fun send(id: String, body: String, asAdmin: Boolean)
    suspend fun accept(id: String)
    suspend fun quote(id: String, amount: Int, note: String)
    suspend fun setStatus(id: String, status: String, note: String)
    suspend fun deliver(id: String, label: String, url: String)
    suspend fun networks(): List<PayNetwork>
    suspend fun payOrder(id: String, network: String, phone: String, name: String): PayStart
    suspend fun payStatus(paymentId: String): String
    suspend fun payOtp(paymentId: String, otp: String)
    suspend fun createMailbox(): Mailbox
    suspend fun mails(box: Mailbox): List<Mail>
    suspend fun phones(): List<Phone>
    suspend fun sms(id: String): List<Sms>
}

// ---------- stockage de la session (téléphone uniquement) ----------
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("africansbiz", Context.MODE_PRIVATE)
    fun load(): Session? {
        val a = prefs.getString("access", null) ?: return null
        return Session(a, prefs.getString("refresh", "") ?: "", prefs.getLong("exp", 0), prefs.getString("email", "") ?: "")
    }
    fun save(s: Session?) {
        prefs.edit().apply {
            if (s == null) clear() else {
                putString("access", s.access); putString("refresh", s.refresh); putLong("exp", s.expiresAt); putString("email", s.email)
            }
        }.apply()
    }
    fun getMailbox(): Mailbox? {
        val a = prefs.getString("mb_addr", null) ?: return null
        return Mailbox(a, prefs.getString("mb_tok", "") ?: "", prefs.getString("mb_exp", "") ?: "", prefs.getLong("mb_cree", 0))
    }
    fun setMailbox(m: Mailbox) {
        prefs.edit().putString("mb_addr", m.address).putString("mb_tok", m.token).putString("mb_exp", m.expiresAt).putLong("mb_cree", m.createdMs).apply()
    }
}

// ---------- lecture JSON ----------
private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k, "")
private fun JSONObject.strOrNull(k: String) = if (!has(k) || isNull(k)) null else optString(k)
private fun JSONObject.intOrNull(k: String) = if (!has(k) || isNull(k)) null else optInt(k)
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun parseService(o: JSONObject) = Service(
    o.str("id"), o.str("fam"), o.str("nom"), o.str("court"), o.optInt("prix"), o.str("type"), o.strOrNull("unite"), o.str("delai"),
    (o.optJSONArray("q") ?: JSONArray()).objects().map { Question(it.str("k"), it.str("label"), it.str("ph")) },
)
private fun parseOrder(o: JSONObject): Order {
    val ans = o.optJSONObject("answers") ?: JSONObject()
    return Order(
        o.str("id"), o.str("ref"), o.str("service_id"), o.str("service_name"), o.str("brief"),
        ans.keys().asSequence().associateWith { ans.optString(it) }, o.intOrNull("amount"), o.str("status"),
        o.str("created_at"), o.optInt("unread"), o.strOrNull("client_email"),
        (o.optJSONArray("events") ?: JSONArray()).objects().map { OrderEvent(it.str("kind"), it.str("label"), it.strOrNull("detail"), it.str("created_at")) },
    )
}

// =========================================================
// Vrai serveur : Supabase (Auth + Edge Function "api")
// =========================================================
class RealBackend(private val url: String, private val anon: String, private val store: SessionStore) : Backend {
    override val demo = false
    var session: Session? = store.load()

    private suspend fun token(): String? {
        val s = session ?: return null
        if (s.refresh.isNotEmpty() && System.currentTimeMillis() / 1000 > s.expiresAt - 60) {
            try {
                val j = raw("POST", "$url/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh), null)
                session = Session(j.str("access_token"), j.str("refresh_token"), j.optLong("expires_at"), s.email)
                store.save(session)
            } catch (e: Exception) {
                session = null; store.save(null); return null
            }
        }
        return session?.access
    }

    private suspend fun raw(method: String, full: String, body: JSONObject?, bearer: String?): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(full).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 25_000
            c.setRequestProperty("apikey", anon)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + (bearer ?: anon))
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = if (text.trim().startsWith("{")) JSONObject(text) else JSONObject()
            if (code >= 400) throw ApiException(j.optString("error", j.optString("msg", "Erreur $code")))
            j
        } finally { c.disconnect() }
    }

    private suspend fun api(method: String, path: String, body: JSONObject? = null) = raw(method, "$url/functions/v1/api$path", body, token())

    override suspend fun sendCode(email: String) {
        raw("POST", "$url/auth/v1/otp", JSONObject().put("email", email).put("create_user", true), null)
    }
    override suspend fun verifyCode(email: String, code: String): Session {
        val j = try { raw("POST", "$url/auth/v1/verify", JSONObject().put("type", "email").put("email", email).put("token", code), null) }
        catch (e: ApiException) { throw ApiException("Code incorrect ou expiré") }
        val s = Session(j.str("access_token"), j.str("refresh_token"), j.optLong("expires_at"), email)
        session = s; store.save(s); return s
    }
    override suspend fun me(): Me { val j = api("GET", "/me"); return Me(j.strOrNull("email"), j.optString("plan", "free"), j.optInt("credits"), j.optString("role", "client")) }
    override suspend fun services() = api("GET", "/services").getJSONArray("services").objects().map(::parseService)
    override suspend fun createOrder(serviceId: String, brief: String, answers: Map<String, String>, whatsapp: String): Order {
        val b = JSONObject().put("service_id", serviceId).put("brief", brief).put("answers", JSONObject(answers as Map<*, *>)).put("whatsapp", whatsapp)
        return parseOrder(api("POST", "/orders", b).getJSONObject("order"))
    }
    override suspend fun orders(all: Boolean) = api("GET", "/orders" + if (all) "?all=1" else "").getJSONArray("orders").objects().map(::parseOrder)
    override suspend fun order(id: String) = parseOrder(api("GET", "/orders/$id").getJSONObject("order"))
    override suspend fun messages(id: String) = api("GET", "/orders/$id/messages").getJSONArray("messages").objects()
        .map { ChatMessage(it.str("id"), it.str("sender"), it.str("body"), it.str("created_at")) }
    override suspend fun send(id: String, body: String, asAdmin: Boolean) { api("POST", "/orders/$id/messages", JSONObject().put("body", body)) }
    override suspend fun accept(id: String) { api("POST", "/orders/$id/accept", JSONObject()) }
    override suspend fun quote(id: String, amount: Int, note: String) { api("POST", "/orders/$id/quote", JSONObject().put("amount", amount).put("note", note)) }
    override suspend fun setStatus(id: String, status: String, note: String) { api("POST", "/orders/$id/status", JSONObject().put("status", status).put("note", note)) }
    override suspend fun deliver(id: String, label: String, url: String) { api("POST", "/orders/$id/deliver", JSONObject().put("label", label).put("url", url)) }
    override suspend fun networks() = api("GET", "/pay/networks").getJSONArray("networks").objects().map { PayNetwork(it.str("code"), it.str("name")) }
    override suspend fun payOrder(id: String, network: String, phone: String, name: String): PayStart {
        val j = api("POST", "/orders/$id/pay", JSONObject().put("network", network).put("phone", phone).put("full_name", name).put("country", "BJ"))
        return PayStart(j.str("payment_id"), j.str("instructions"), j.optBoolean("otp_after_push"), j.strOrNull("checkout_url"))
    }
    override suspend fun payStatus(paymentId: String) = api("GET", "/pay/status?id=" + URLEncoder.encode(paymentId, "UTF-8")).optString("status", "PENDING")
    override suspend fun payOtp(paymentId: String, otp: String) { api("POST", "/pay/otp", JSONObject().put("payment_id", paymentId).put("otp", otp)) }
    override suspend fun createMailbox(): Mailbox {
        val j = api("POST", "/mailbox", JSONObject())
        return Mailbox(j.str("address"), j.str("token"), j.str("expires_at"), System.currentTimeMillis())
    }
    override suspend fun mails(box: Mailbox) = api("GET", "/mailbox/messages?address=" + URLEncoder.encode(box.address, "UTF-8") + "&token=" + URLEncoder.encode(box.token, "UTF-8"))
        .getJSONArray("messages").objects().map { Mail(it.str("id"), it.str("from_addr"), it.str("subject"), it.str("body_html"), it.str("body_text"), it.str("received_at")) }
    override suspend fun phones() = api("GET", "/numbers").getJSONArray("numbers").objects()
        .map { Phone(it.str("id"), it.str("e164"), it.str("country"), it.optInt("sms_24h"), it.strOrNull("last_sms_at")) }
    override suspend fun sms(id: String) = api("GET", "/numbers/$id/sms").getJSONArray("sms").objects()
        .map { Sms(it.str("sender"), it.str("body"), it.str("received_at")) }
}

// =========================================================
// Mode démo : tout est simulé dans le téléphone
// =========================================================
class DemoBackend : Backend {
    override val demo = true
    private var seq = 41
    private fun ref() = "AB-2611-" + (++seq).toString().padStart(4, '0')
    private val services = listOf(
        Service("site-vitrine", "creer", "Site web vitrine", "Un site rapide et beau pour présenter ton activité, avec formulaire de contact et WhatsApp.", 50000, "devis", null, "7 à 10 jours", listOf(Question("activite", "Nom de ton activité", "Ex : Boutique Awa Couture"))),
        Service("app-android", "creer", "Application Android", "Une vraie application pour tes clients ou ton équipe, publiée en APK ou sur le Play Store.", 150000, "devis", null, "3 à 6 semaines", listOf(Question("fonctions", "Fonctions principales", "Connexion, catalogue, notifications…"))),
        Service("robot-whatsapp", "creer", "Robot WhatsApp", "Il répond à tes clients, prend les commandes et envoie des rapports, 24 h/24.", 40000, "devis", null, "5 à 10 jours", listOf(Question("taches", "Que doit faire le robot ?", ""))),
        Service("chaine-video", "automatiser", "Chaîne de production vidéo", "Tes vidéos créées et publiées automatiquement chaque jour sur TikTok et YouTube.", 75000, "devis", null, "1 à 2 semaines", listOf(Question("theme", "Thème des vidéos", "Contes, histoire, produits…"))),
        Service("maintenance", "gerer", "Gestion et maintenance mensuelle", "Mises à jour, sauvegardes, corrections et petites évolutions de ton site ou de ton app.", 15000, "fixe", "/ mois", "Démarre sous 48 h", emptyList()),
        Service("claude-code", "installer", "Claude Code sur Android", "Installation guidée à distance dans Termux. Abonnement Claude à ta charge.", 5000, "fixe", null, "Le jour même", listOf(Question("telephone", "Modèle de téléphone et version d'Android", "Ex : Redmi Note 13, Android 14"))),
        Service("antigravity", "installer", "Antigravity CLI sur Android", "Installation de l'agent Google Antigravity (agy) dans Termux, avec ton compte Google.", 5000, "fixe", null, "Le jour même", emptyList()),
        Service("dossier-projet", "rediger", "Dossier de projet ou candidature", "Un dossier clair et convaincant pour un concours ou un appel à projets.", 20000, "devis", null, "3 à 7 jours", emptyList()),
    )
    private class Cmd(var o: Order, val msgs: MutableList<ChatMessage>)
    private val cmds = mutableListOf(
        Cmd(Order("c1", ref(), "robot-whatsapp", "Robot WhatsApp", "Robot pour ma boutique de tissus : prix, commandes et résumé du soir.", emptyMap(), 60000, "en_cours", nowIso(4320), 1, "awa.sossou@gmail.com",
            listOf(OrderEvent("status", "Commande reçue", null, nowIso(4320)), OrderEvent("status", "Devis envoyé : 60 000 F", "Robot + rapport du soir", nowIso(4000)), OrderEvent("status", "Paiement confirmé", "MTN MoMo", nowIso(3900)), OrderEvent("note", "Menu des prix terminé", "Le robot répond déjà aux 3 catégories.", nowIso(300)))),
            mutableListOf(ChatMessage("m1", "client", "Est-ce que le robot pourra envoyer des photos des tissus ?", nowIso(3700)), ChatMessage("m2", "admin", "Oui ! Il enverra la photo avec le prix. Envoie-moi tes photos ici.", nowIso(3690)), ChatMessage("m3", "admin", "Le menu des prix est prêt. Je te montre une vidéo demain matin.", nowIso(300)))),
        Cmd(Order("c2", ref(), "site-vitrine", "Site web vitrine", "Site pour mon salon de coiffure à Cotonou, avec prise de rendez-vous.", emptyMap(), 85000, "a_payer", nowIso(1500), 1, "awa.sossou@gmail.com",
            listOf(OrderEvent("status", "Commande reçue", null, nowIso(1500)), OrderEvent("status", "Devis envoyé : 85 000 F", "5 pages + rendez-vous WhatsApp + domaine 1 an", nowIso(120)))),
            mutableListOf(ChatMessage("m4", "admin", "Voici le devis : 85 000 F, livraison en 10 jours.", nowIso(120)))),
    )
    private var paiement: Triple<String, String, Long>? = null
    private val replies = listOf("Bien reçu, je regarde ça tout de suite.", "Merci ! Je te fais un retour dans la journée.", "Parfait, c'est noté dans ta commande.")
    private fun find(id: String) = cmds.firstOrNull { it.o.id == id } ?: throw ApiException("Commande introuvable")
    private fun event(c: Cmd, kind: String, label: String, detail: String?) { c.o = c.o.copy(events = c.o.events + OrderEvent(kind, label, detail, nowIso())) }

    override suspend fun sendCode(email: String) { delay(400) }
    override suspend fun verifyCode(email: String, code: String): Session { delay(300); return Session("demo", "", Long.MAX_VALUE, email) }
    override suspend fun me() = Me("toi@africansbiz.com", "free", 0, "admin")
    override suspend fun services() = services
    override suspend fun createOrder(serviceId: String, brief: String, answers: Map<String, String>, whatsapp: String): Order {
        delay(400); val s = services.first { it.id == serviceId }
        val o = Order("c" + System.currentTimeMillis(), ref(), s.id, s.nom, brief, answers, if (s.fixe) s.prix else null, if (s.fixe) "a_payer" else "devis", nowIso(), 0, "toi@africansbiz.com", listOf(OrderEvent("status", "Commande reçue", null, nowIso())))
        cmds.add(0, Cmd(o, mutableListOf())); return o
    }
    override suspend fun orders(all: Boolean) = cmds.map { it.o }
    override suspend fun order(id: String) = find(id).o
    override suspend fun messages(id: String): List<ChatMessage> { val c = find(id); c.o = c.o.copy(unread = 0); return c.msgs.toList() }
    override suspend fun send(id: String, body: String, asAdmin: Boolean) {
        val c = find(id); c.msgs.add(ChatMessage("m" + System.nanoTime(), if (asAdmin) "admin" else "client", body, nowIso()))
        if (!asAdmin) { delay(1200); c.msgs.add(ChatMessage("m" + System.nanoTime(), "admin", replies[Random.nextInt(replies.size)], nowIso())) }
    }
    override suspend fun accept(id: String) { val c = find(id); c.o = c.o.copy(status = "livree"); event(c, "status", "Livraison validée par le client", null) }
    override suspend fun quote(id: String, amount: Int, note: String) { val c = find(id); c.o = c.o.copy(amount = amount, status = "a_payer"); event(c, "status", "Devis envoyé : " + francs(amount), note) }
    override suspend fun setStatus(id: String, status: String, note: String) { val c = find(id); c.o = c.o.copy(status = status); event(c, "status", STATUTS[status] ?: status, note) }
    override suspend fun deliver(id: String, label: String, url: String) { val c = find(id); c.o = c.o.copy(status = "validation"); event(c, "delivery", label, url) }
    override suspend fun networks() = listOf(PayNetwork("mtn_bj", "MTN MoMo"), PayNetwork("moov_bj", "Moov Money"), PayNetwork("celtiis_bj", "Celtiis Cash"))
    override suspend fun payOrder(id: String, network: String, phone: String, name: String): PayStart {
        delay(600); val p = "P" + System.currentTimeMillis(); paiement = Triple(p, id, System.currentTimeMillis())
        return PayStart(p, "Démo : le paiement sera confirmé dans 5 secondes.", false, null)
    }
    override suspend fun payStatus(paymentId: String): String {
        val p = paiement ?: return "FAILED"
        if (p.first != paymentId) return "FAILED"
        if (System.currentTimeMillis() - p.third < 5000) return "PENDING"
        val c = find(p.second)
        if (c.o.status == "a_payer") { c.o = c.o.copy(status = "payee"); event(c, "status", "Paiement confirmé", "Mobile Money") }
        return "SUCCESS"
    }
    override suspend fun payOtp(paymentId: String, otp: String) {}
    override suspend fun createMailbox(): Mailbox {
        val n = listOf("kofi", "ama", "sena", "yao", "afi", "edem")[Random.nextInt(6)] + "." + Random.nextInt(1000, 9999)
        return Mailbox("$n@africansbiz.com", "demo", nowIso(-60), System.currentTimeMillis())
    }
    override suspend fun mails(box: Mailbox): List<Mail> {
        val age = System.currentTimeMillis() - box.createdMs
        return buildList {
            if (age > 12_000) add(Mail("2", "MaBoutique (exemple) <no-reply@exemple.com>", "Confirme ton adresse", "", "Ton code de confirmation : 482913", nowIso()))
            if (age > 4_000) add(Mail("1", "AfricansBiz <bonjour@africansbiz.com>", "Ta boîte temporaire est prête", "", "Les mails envoyés à ${box.address} arrivent ici automatiquement.", nowIso()))
        }
    }
    private val demoPhones = listOf(Phone("BJ1", "+2290196123456", "BJ", 7, nowIso(3)), Phone("BJ2", "+2290154987321", "BJ", 4, nowIso(12)), Phone("TG1", "+22890123456", "TG", 5, nowIso(20)), Phone("CI1", "+2250712345678", "CI", 3, nowIso(45)), Phone("SN1", "+221771234567", "SN", 2, nowIso(80)))
    override suspend fun phones() = demoPhones
    override suspend fun sms(id: String) = listOf(
        Sms("WhatsApp", "Ton code WhatsApp : ${Random.nextInt(100, 999)}-${Random.nextInt(100, 999)}. Ne le partage avec personne.", nowIso(1)),
        Sms("Telegram", "Code de connexion Telegram : ${Random.nextInt(10000, 99999)}.", nowIso(9)),
        Sms("Google", "G-${Random.nextInt(100000, 999999)} est votre code de validation Google.", nowIso(31)),
    )
}
