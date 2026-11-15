@file:OptIn(ExperimentalMaterial3Api::class)

package com.africansbiz.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =========================================================
// Navigation
// =========================================================
sealed interface Ecran {
    data object Accueil : Ecran
    data class Services(val famille: String = "tout") : Ecran
    data object Commandes : Ecran
    data object Outils : Ecran
    data object Compte : Ecran
    data class Commander(val service: Service) : Ecran
    data class Commande(val id: String, val admin: Boolean) : Ecran
}

class AppState(val api: Backend, val store: SessionStore, private val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var session by mutableStateOf(if (api.demo) null else store.load())
    var me by mutableStateOf(Me(null, "free", 0, "client"))
    var services by mutableStateOf<List<Service>>(emptyList())
    val pile = mutableStateListOf<Ecran>(Ecran.Accueil)
    fun aller(e: Ecran) { pile.add(e) }
    fun onglet(e: Ecran) { pile.clear(); pile.add(e) }
    fun retour() { if (pile.size > 1) pile.removeAt(pile.lastIndex) }
    fun toast(m: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(m) } }
    fun lancer(bloc: suspend () -> Unit) = scope.launch {
        try { bloc() } catch (e: Exception) { toast(e.message ?: "Erreur réseau, réessaie") }
    }
    suspend fun rafraichirMoi() { me = if (session == null) Me(null, "free", 0, "client") else try { api.me() } catch (e: Exception) { me } }
    fun exigerConnexion(): Boolean {
        if (session != null) return true
        toast("Connecte-toi d'abord"); aller(Ecran.Compte); return false
    }
    fun deconnecter() { session = null; store.save(null); me = Me(null, "free", 0, "client"); onglet(Ecran.Accueil) }
}

@Composable
fun App(api: Backend, store: SessionStore) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s = remember { AppState(api, store, scope, snackbar) }
    LaunchedEffect(Unit) {
        try { s.services = api.services() } catch (_: Exception) { }
        s.rafraichirMoi()
    }
    BackHandler(enabled = s.pile.size > 1) { s.retour() }
    val ecran = s.pile.last()
    val principal = ecran is Ecran.Accueil || ecran is Ecran.Services || ecran is Ecran.Commandes || ecran is Ecran.Outils

    Scaffold(
        topBar = {
            if (principal) TopAppBar(
                title = { Logo() },
                actions = {
                    if (api.demo) AssistChip(onClick = { s.toast("Mode démo : rien n'est réellement payé ni envoyé") }, label = { Text("Démo") })
                    IconButton(onClick = { s.aller(Ecran.Compte) }) { Icon(Icons.Outlined.Person, "Mon compte") }
                },
            )
        },
        bottomBar = {
            if (principal) NavigationBar {
                val items = listOf<Triple<Ecran, String, ImageVector>>(
                    Triple(Ecran.Accueil, "Accueil", Icons.Outlined.Home), Triple(Ecran.Services(), "Services", Icons.Outlined.GridView),
                    Triple(Ecran.Commandes, "Commandes", Icons.Outlined.Receipt), Triple(Ecran.Outils, "Outils", Icons.Outlined.Build),
                )
                items.forEach { (e, label, icone) ->
                    NavigationBarItem(
                        selected = ecran::class == e::class, onClick = { s.onglet(e) },
                        icon = { Icon(icone, null) }, label = { Text(label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (ecran) {
                is Ecran.Accueil -> Accueil(s)
                is Ecran.Services -> Services(s, ecran.famille)
                is Ecran.Commandes -> Commandes(s)
                is Ecran.Outils -> Outils(s)
                is Ecran.Compte -> Compte(s)
                is Ecran.Commander -> Commander(s, ecran.service)
                is Ecran.Commande -> CommandeDetail(s, ecran.id, ecran.admin)
            }
        }
    }
}

// =========================================================
// Éléments de marque
// =========================================================
@Composable
fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF23306E)), contentAlignment = Alignment.Center) {
            Text("A", color = Color(0xFFE2B65A), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("Africans", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
        Text("Biz", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun SousTitre(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)

/** Fil de perles : l'avancement de la commande, comme des perles enfilées */
@Composable
fun FilDePerles(statut: String, grand: Boolean = false, modifier: Modifier = Modifier) {
    val m = LocalMarque.current
    val fond = MaterialTheme.colorScheme.surfaceContainerLowest
    val rang = rangStatut(statut)
    val r = if (grand) 8.dp else 5.5.dp
    Canvas(modifier.fillMaxWidth().height(if (grand) 26.dp else 18.dp)) {
        val rad = r.toPx(); val n = ETAPES.size
        val pas = (size.width - rad * 4) / (n - 1); val y = size.height / 2
        for (i in 0 until n - 1) {
            val x1 = rad * 2 + i * pas; val x2 = x1 + pas
            drawLine(if (i + 1 <= rang) m.laiton else m.perleVide, Offset(x1, y), Offset(x2, y), strokeWidth = 2.dp.toPx())
        }
        for (i in 0 until n) {
            val c = Offset(rad * 2 + i * pas, y)
            when {
                statut == "livree" || i < rang -> drawCircle(m.laiton, rad, c)
                i == rang -> {
                    drawCircle(m.laiton.copy(alpha = .22f), rad * 1.9f, c)
                    drawCircle(fond, rad, c)
                    drawCircle(m.laiton, rad, c, style = Stroke(2.dp.toPx()))
                }
                else -> drawCircle(m.perleVide, rad, c)
            }
        }
    }
}

@Composable
fun PuceStatut(statut: String) {
    val cs = MaterialTheme.colorScheme; val m = LocalMarque.current
    val (fond, texte) = when (statut) {
        "a_payer", "validation" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "payee", "en_cours" -> cs.primaryContainer to cs.onPrimaryContainer
        "livree" -> m.palmePale to m.palme
        "annulee" -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Surface(color = fond, shape = CircleShape) {
        Text(STATUTS[statut] ?: statut, color = texte, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Bordereau : carte de commande avec bord perforé */
@Composable
fun Bordereau(o: Order, admin: Boolean, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = { onClick?.invoke() }, enabled = onClick != null,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest, disabledContainerColor = cs.surfaceContainerLowest, disabledContentColor = cs.onSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, disabledElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            val trait = cs.outlineVariant; val fondPage = cs.surface
            Canvas(Modifier.width(16.dp).fillMaxHeight()) {
                var y = 10.dp.toPx()
                while (y < size.height - 6.dp.toPx()) { drawCircle(fondPage, 3.5.dp.toPx(), Offset(0f, y)); y += 12.dp.toPx() }
                drawLine(trait, Offset(size.width - 2.dp.toPx(), 12.dp.toPx()), Offset(size.width - 2.dp.toPx(), size.height - 12.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            Column(Modifier.padding(start = 10.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("${o.ref} · ${ilYa(o.createdAt)}", style = Mono.copy(fontSize = 12.sp), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(o.serviceName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        if (admin && o.clientEmail != null) Text(o.clientEmail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text(if (o.amount != null) francs(o.amount) else "devis", style = Mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(10.dp))
                FilDePerles(o.status, modifier = Modifier.widthIn(max = 260.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PuceStatut(o.status)
                    Spacer(Modifier.weight(1f))
                    if (o.unread > 0) Badge { Text("${o.unread}") }
                }
            }
        }
    }
}

@Composable
fun BarreRetour(s: AppState, titre: String, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(titre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { s.retour() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        actions = actions, windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
fun CarteSection(titre: String? = null, contenu: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            if (titre != null) { Text(titre, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(10.dp)) }
            contenu()
        }
    }
}

// =========================================================
// ACCUEIL
// =========================================================
@Composable
fun Accueil(s: AppState) {
    val cs = MaterialTheme.colorScheme; val m = LocalMarque.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF23306E), contentColor = Color.White), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text("L'ATELIER NUMÉRIQUE DE L'AFRIQUE", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE2B65A))
                    Spacer(Modifier.height(12.dp))
                    Text("Tu commandes.\nOn construit.", style = MaterialTheme.typography.displaySmall)
                    Text("Tu suis tout.", style = MaterialTheme.typography.displaySmall, color = Color(0xFFE2B65A))
                    Spacer(Modifier.height(12.dp))
                    Text("Sites, applications Android, robots WhatsApp, chaînes vidéo… Suis chaque étape et discute avec ton développeur dans un chat privé.",
                        style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .82f))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { s.onglet(Ecran.Services()) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2B65A), contentColor = Color(0xFF1E1400))) { Text("Voir les services") }
                        OutlinedButton(onClick = { s.onglet(Ecran.Commandes) }, border = BorderStroke(1.dp, Color.White.copy(alpha = .5f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Mes commandes") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)); SousTitre("Comment ça marche"); Spacer(Modifier.height(6.dp)); Text("Quatre étapes, aucune surprise", style = MaterialTheme.typography.headlineSmall) }
        item {
            val etapes = listOf(
                "Commande" to "Choisis un service et décris ton besoin.",
                "Paiement" to "Prix fixe ou devis sous 24 h, payé par MTN, Moov ou Celtiis.",
                "Suivi" to "Chaque étape s'affiche sur ton bordereau, avec un chat privé.",
                "Livraison" to "Tu testes ton travail et tu valides.",
            )
            Column {
                etapes.forEachIndexed { i, (t, d) ->
                    Row {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(cs.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(34.dp)) { drawCircle(m.laiton, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
                                Text("${i + 1}", color = cs.tertiary, fontWeight = FontWeight.Bold)
                            }
                            if (i < etapes.lastIndex) Canvas(Modifier.width(2.dp).height(30.dp)) {
                                drawLine(m.laiton, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.padding(top = 4.dp)) {
                            Text(t, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(d, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)); SousTitre("Ce qu'on fait"); Spacer(Modifier.height(6.dp)); Text("Tout ton numérique au même endroit", style = MaterialTheme.typography.headlineSmall) }
        val textes = mapOf(
            "creer" to "Sites, boutiques, applications Android et robots.",
            "automatiser" to "Chaînes vidéo, publications automatiques, données.",
            "gerer" to "Hébergement, maintenance, applications de gestion.",
            "installer" to "Claude Code, Antigravity CLI et assistants IA sur Android.",
            "rediger" to "Dossiers de projet et plateformes pour écoles.",
        )
        items(FAMILLES.entries.toList()) { (id, nom) ->
            Card(onClick = { s.onglet(Ecran.Services(id)) }, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(nom, style = MaterialTheme.typography.titleLarge)
                        Text(textes[id] ?: "", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    val n = s.services.count { it.fam == id }
                    if (n > 0) Text("$n", style = Mono.copy(fontSize = 13.sp), color = cs.tertiary)
                }
            }
        }
        item {
            CarteSection {
                listOf(
                    Triple(Icons.Outlined.Lock, "Chat privé par commande", "Seuls toi et ton développeur lisez la discussion."),
                    Triple(Icons.Outlined.PhoneAndroid, "Mobile Money intégré", "La demande de paiement arrive sur ton téléphone."),
                    Triple(Icons.Filled.Check, "Tu valides la livraison", "La commande se termine quand tout fonctionne."),
                ).forEachIndexed { i, (ic, t, d) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cs.outlineVariant)
                    Row { Icon(ic, null, tint = cs.tertiary); Spacer(Modifier.width(12.dp)); Column { Text(t, fontWeight = FontWeight.SemiBold); Text(d, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) } }
                }
            }
        }
    }
}

// =========================================================
// CATALOGUE
// =========================================================
@Composable
fun Services(s: AppState, familleInitiale: String) {
    var famille by remember(familleInitiale) { mutableStateOf(familleInitiale) }
    val cs = MaterialTheme.colorScheme
    val liste = s.services.filter { famille == "tout" || it.fam == famille }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SousTitre("Catalogue"); Text("Que veux-tu construire ?", style = MaterialTheme.typography.headlineMedium) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("tout" to "Tout") + FAMILLES.entries.map { it.key to it.value }) { (id, nom) ->
                    FilterChip(selected = famille == id, onClick = { famille = id }, label = { Text(nom) })
                }
            }
        }
        if (s.services.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        items(liste, key = { it.id }) { svc ->
            Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    SousTitre(FAMILLES[svc.fam] ?: "")
                    Text(svc.nom, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(svc.court, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (svc.fixe) "" else "dès ") + francs(svc.prix), style = MaterialTheme.typography.titleLarge)
                            Text(if (svc.fixe) "prix fixe ${svc.unite ?: ""}" else "devis gratuit · ${svc.delai}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Button(onClick = { if (s.exigerConnexion()) s.aller(Ecran.Commander(svc)) }) { Text("Commander") }
                    }
                }
            }
        }
    }
}

// =========================================================
// PASSER COMMANDE
// =========================================================
@Composable
fun Commander(s: AppState, svc: Service) {
    var brief by remember { mutableStateOf("") }
    val reponses = remember { mutableStateMapOf<String, String>() }
    var whatsapp by remember { mutableStateOf("") }
    var envoi by remember { mutableStateOf(false) }
    var aPayer by remember { mutableStateOf<Order?>(null) }
    Column(Modifier.fillMaxSize()) {
        BarreRetour(s, svc.nom)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
            Text(if (svc.fixe) "Prix fixe ${francs(svc.prix)} ${svc.unite ?: ""} · ${svc.delai}" else "Devis gratuit sous 24 h · ${svc.delai}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(brief, { brief = it }, label = { Text("Décris ton besoin") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            svc.questions.forEach { q ->
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(reponses[q.label] ?: "", { reponses[q.label] = it }, label = { Text(q.label) },
                    placeholder = { if (q.ph.isNotEmpty()) Text(q.ph) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(whatsapp, { whatsapp = it }, label = { Text("Ton numéro WhatsApp") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), supportingText = { Text("Pour te prévenir quand ta commande avance") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !envoi, modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    if (brief.trim().length < 10) { s.toast("Décris ton besoin en quelques mots"); return@Button }
                    envoi = true
                    s.lancer {
                        try {
                            val o = s.api.createOrder(svc.id, brief.trim(), reponses.filterValues { it.isNotBlank() }, whatsapp.trim())
                            if (svc.fixe) aPayer = o
                            else { s.toast("Demande envoyée : devis sous 24 h"); s.onglet(Ecran.Commandes); s.aller(Ecran.Commande(o.id, false)) }
                        } finally { envoi = false }
                    }
                },
            ) { if (envoi) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (svc.fixe) "Commander et payer ${francs(svc.prix)}" else "Envoyer ma demande de devis") }
            Spacer(Modifier.height(24.dp))
        }
    }
    aPayer?.let { o ->
        Paiement(s, o, onFerme = { aPayer = null; s.onglet(Ecran.Commandes); s.aller(Ecran.Commande(o.id, false)) },
            onPaye = { aPayer = null; s.onglet(Ecran.Commandes); s.aller(Ecran.Commande(o.id, false)) })
    }
}

// =========================================================
// PAIEMENT SASPAY (push direct sur le téléphone, sans redirection)
// =========================================================
@Composable
fun Paiement(s: AppState, o: Order, onFerme: () -> Unit, onPaye: () -> Unit) {
    var reseaux by remember { mutableStateOf<List<PayNetwork>>(emptyList()) }
    var reseau by remember { mutableStateOf<String?>(null) }
    var nom by remember { mutableStateOf("") }
    var tel by remember { mutableStateOf("") }
    var envoi by remember { mutableStateOf(false) }
    var attente by remember { mutableStateOf<PayStart?>(null) }
    var otp by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try { reseaux = s.api.networks(); reseau = reseaux.firstOrNull()?.code } catch (e: Exception) { s.toast("Paiement indisponible") } }
    LaunchedEffect(attente?.paymentId) {
        val p = attente ?: return@LaunchedEffect
        repeat(60) {
            delay(4000)
            val st = try { s.api.payStatus(p.paymentId) } catch (_: Exception) { "PENDING" }
            if (st == "SUCCESS") { s.toast("Paiement reçu, merci !"); onPaye(); return@LaunchedEffect }
            if (st == "FAILED" || st == "CANCELLED") { s.toast("Paiement refusé ou annulé"); attente = null; return@LaunchedEffect }
        }
    }
    Dialog(onDismissRequest = onFerme, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth(.92f)) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("Paiement Mobile Money", style = MaterialTheme.typography.titleLarge)
                Text("${o.ref} · ${o.serviceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                val p = attente
                if (p == null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp)) { Text("Montant", Modifier.weight(1f)); Text(francs(o.amount), style = Mono.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Ton réseau", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(reseaux) { n -> FilterChip(selected = reseau == n.code, onClick = { reseau = n.code }, label = { Text(n.name) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(nom, { nom = it }, label = { Text("Nom complet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(tel, { tel = it }, label = { Text("Numéro Mobile Money") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Button(enabled = !envoi && reseau != null, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        if (nom.trim().length < 3) { s.toast("Entre ton nom complet"); return@Button }
                        if (tel.filter { it.isDigit() }.length < 8) { s.toast("Numéro invalide"); return@Button }
                        envoi = true
                        s.lancer {
                            try {
                                val r = s.api.payOrder(o.id, reseau!!, tel.filter { it.isDigit() }, nom.trim())
                                if (!r.checkoutUrl.isNullOrBlank()) s.toast("Ce réseau n'accepte pas le paiement direct : choisis-en un autre")
                                else attente = r
                            } finally { envoi = false }
                        }
                    }) { if (envoi) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Payer ${francs(o.amount)}") }
                    Spacer(Modifier.height(8.dp))
                    Text("La demande arrive sur ton téléphone. Tu restes dans AfricansBiz.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Valide sur ton téléphone", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(p.instructions, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.otpAfterPush) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(otp, { otp = it }, label = { Text("Code reçu par SMS") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { s.lancer { s.api.payOtp(p.paymentId, otp.trim()); s.toast("Code envoyé, vérification…") } }, modifier = Modifier.fillMaxWidth()) { Text("Confirmer le code") }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onFerme, modifier = Modifier.fillMaxWidth()) { Text("Fermer") }
                    Text("Tu peux fermer : tout s'activera dès la confirmation.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

// =========================================================
// MES COMMANDES (+ vue admin)
// =========================================================
@Composable
fun Commandes(s: AppState) {
    var tout by remember { mutableStateOf(false) }
    var liste by remember { mutableStateOf<List<Order>?>(null) }
    var cle by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.session, tout, cle) {
        if (s.session == null) return@LaunchedEffect
        liste = null
        liste = try { s.api.orders(tout) } catch (e: Exception) { s.toast(e.message ?: "Erreur"); emptyList() }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SousTitre(if (tout) "Administration" else "Espace client"); Text(if (tout) "Toutes les commandes" else "Mes commandes", style = MaterialTheme.typography.headlineMedium) }
                if (s.session != null) IconButton(onClick = { cle++ }) { Icon(Icons.Outlined.Refresh, "Actualiser") }
            }
        }
        if (s.session == null) {
            item {
                CarteSection {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(8.dp))
                    Text("Connecte-toi pour voir tes commandes", style = MaterialTheme.typography.titleMedium)
                    Text("Ton espace est privé : commandes, suivi et discussions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { s.aller(Ecran.Compte) }) { Text("Me connecter") }
                }
            }
            return@LazyColumn
        }
        if (s.me.role == "admin") item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !tout, onClick = { tout = false }, label = { Text("Mes commandes") })
                FilterChip(selected = tout, onClick = { tout = true }, label = { Text("Toutes (admin)") })
            }
        }
        val l = liste
        when {
            l == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            l.isEmpty() -> item {
                CarteSection {
                    Text("Aucune commande pour l'instant", style = MaterialTheme.typography.titleMedium)
                    Text("Choisis un service : ton premier bordereau apparaîtra ici.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp)); Button(onClick = { s.onglet(Ecran.Services()) }) { Text("Voir les services") }
                }
            }
            else -> items(l, key = { it.id }) { o -> Bordereau(o, tout) { s.aller(Ecran.Commande(o.id, tout)) } }
        }
    }
}

// =========================================================
// DÉTAIL D'UNE COMMANDE : suivi + chat sécurisé
// =========================================================
@Composable
fun CommandeDetail(s: AppState, id: String, admin: Boolean) {
    var o by remember { mutableStateOf<Order?>(null) }
    var onglet by remember { mutableIntStateOf(0) }
    var cle by remember { mutableIntStateOf(0) }
    var payer by remember { mutableStateOf(false) }
    LaunchedEffect(id, cle) { try { o = s.api.order(id) } catch (e: Exception) { s.toast(e.message ?: "Erreur") } }
    Column(Modifier.fillMaxSize()) {
        BarreRetour(s, o?.ref ?: "Commande") { IconButton(onClick = { cle++ }) { Icon(Icons.Outlined.Refresh, "Actualiser") } }
        TabRow(selectedTabIndex = onglet) {
            Tab(selected = onglet == 0, onClick = { onglet = 0 }, text = { Text("Suivi") })
            Tab(selected = onglet == 1, onClick = { onglet = 1 }, text = { Text("Discussion") })
        }
        val c = o
        if (c == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (onglet == 0) Suivi(s, c, admin, onPayer = { payer = true }, onChange = { cle++ })
        else Discussion(s, c.id, admin)
    }
    if (payer) o?.let { c -> Paiement(s, c, onFerme = { payer = false; cle++ }, onPaye = { payer = false; cle++ }) }
}

@Composable
fun Suivi(s: AppState, c: Order, admin: Boolean, onPayer: () -> Unit, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val m = LocalMarque.current; val uri = LocalUriHandler.current
    val rang = minOf(rangStatut(c.status), ETAPES.lastIndex)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CarteSection {
                Text("${c.ref} · ${ilYa(c.createdAt)}", style = Mono.copy(fontSize = 12.sp), color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Top) {
                    Text(c.serviceName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text(if (c.amount != null) francs(c.amount) else "devis", style = Mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                }
                if (admin && c.clientEmail != null) Text(c.clientEmail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                FilDePerles(c.status, grand = true)
                Spacer(Modifier.height(6.dp))
                Text("Étape ${rang + 1}/6 · ${ETAPES[rang]}", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!admin) {
            val (titre, texte, bouton) = when (c.status) {
                "a_payer" -> Triple("Devis prêt", "${francs(c.amount)} à payer pour démarrer", "Payer maintenant")
                "validation" -> Triple("Ta livraison est prête", "Teste-la puis valide", "Valider la livraison")
                "devis" -> Triple("En cours d'étude", "Ton devis arrive sous 24 h", null)
                else -> Triple(null, null, null)
            }
            if (titre != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(titre, style = MaterialTheme.typography.bodySmall)
                        Text(texte ?: "", style = MaterialTheme.typography.titleLarge)
                        if (bouton != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                if (c.status == "a_payer") onPayer()
                                else s.lancer { s.api.accept(c.id); s.toast("Merci ! Commande terminée"); onChange() }
                            }) { Text(bouton) }
                        }
                    }
                }
            }
        }
        if (admin) item { ActionsAdmin(s, c, onChange) }
        val livrables = c.events.filter { it.kind == "delivery" }
        if (livrables.isNotEmpty()) item {
            CarteSection("Livrables") {
                livrables.forEach { l ->
                    Surface(color = m.palmePale, contentColor = m.palme, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { l.detail?.let { runCatching { uri.openUri(it) } } }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Inventory2, null); Spacer(Modifier.width(10.dp)); Text(l.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            CarteSection("Historique") {
                c.events.reversed().forEach { e ->
                    Row(Modifier.padding(bottom = 12.dp)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (e.kind == "delivery") m.palmePale else cs.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = if (e.kind == "delivery") m.palme else cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(e.label, fontWeight = FontWeight.SemiBold)
                            if (!e.detail.isNullOrBlank() && e.kind != "delivery") Text(e.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Text(dateHeure(e.createdAt), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            CarteSection(if (admin) "Demande du client" else "Ta demande") {
                Text(c.brief, style = MaterialTheme.typography.bodyMedium)
                c.answers.forEach { (k, v) -> Spacer(Modifier.height(6.dp)); Text(k, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant); Text(v) }
            }
        }
    }
}

@Composable
fun ActionsAdmin(s: AppState, c: Order, onChange: () -> Unit) {
    var montant by remember { mutableStateOf("") }
    var noteDevis by remember { mutableStateOf("") }
    var statut by remember(c.status) { mutableStateOf(c.status) }
    var note by remember { mutableStateOf("") }
    var livNom by remember { mutableStateOf("") }
    var livUrl by remember { mutableStateOf("") }
    CarteSection("Actions administrateur") {
        Text("Devis", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(montant, { montant = it.filter { ch -> ch.isDigit() } }, label = { Text("Montant (F)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(noteDevis, { noteDevis = it }, label = { Text("Ce qui est inclus") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val mnt = montant.toIntOrNull() ?: 0
            if (mnt < 100) s.toast("Montant invalide") else s.lancer { s.api.quote(c.id, mnt, noteDevis.trim()); s.toast("Devis envoyé"); onChange() }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Envoyer le devis") }

        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Étape", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items(STATUTS.entries.toList()) { (k, v) -> FilterChip(selected = statut == k, onClick = { statut = k }, label = { Text(v) }) }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note visible par le client") }, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(onClick = { s.lancer { s.api.setStatus(c.id, statut, note.trim()); s.toast("Étape mise à jour"); note = ""; onChange() } },
            modifier = Modifier.padding(top = 8.dp)) { Text("Mettre à jour") }

        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Livraison", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(livNom, { livNom = it }, label = { Text("Nom du livrable (ex : APK v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(livUrl, { livUrl = it.trim() }, label = { Text("Lien https://…") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (livNom.isBlank() || !livUrl.startsWith("https://")) s.toast("Nom et lien https requis")
            else s.lancer { s.api.deliver(c.id, livNom.trim(), livUrl); s.toast("Livrable envoyé au client"); livNom = ""; livUrl = ""; onChange() }
        }, colors = ButtonDefaults.buttonColors(containerColor = LocalMarque.current.laiton, contentColor = Color(0xFF1E1400)), modifier = Modifier.padding(top = 8.dp)) { Text("Livrer") }
    }
}

@Composable
fun Discussion(s: AppState, id: String, admin: Boolean) {
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var texte by remember { mutableStateOf("") }
    var cle by remember { mutableIntStateOf(0) }
    val etat = rememberLazyListState()
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(id, cle) {
        while (true) {
            try { messages = s.api.messages(id) } catch (_: Exception) { }
            delay(3000)
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) etat.animateScrollToItem(messages.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = LocalMarque.current.palme, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (admin) "Privée : visible seulement par toi et le client" else "Privée : visible seulement par toi et ton développeur", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        LazyColumn(state = etat, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) item { Text("Pose ici tes questions sur la commande.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
            items(messages, key = { it.id }) { msg ->
                val mien = if (admin) msg.sender == "admin" else msg.sender == "client"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mien) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (mien) cs.primary else cs.surfaceContainerLowest, contentColor = if (mien) cs.onPrimary else cs.onSurface,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mien) 16.dp else 4.dp, bottomEnd = if (mien) 4.dp else 16.dp),
                        tonalElevation = if (mien) 0.dp else 1.dp, modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            Text(msg.body)
                            Text(heure(msg.createdAt), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal),
                                modifier = Modifier.align(Alignment.End).padding(top = 2.dp), color = LocalContentColor.current.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(cs.surfaceContainerLowest).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(texte, { texte = it }, placeholder = { Text("Écris ton message…") }, maxLines = 4, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val t = texte.trim(); if (t.isEmpty()) return@FilledIconButton
                texte = ""
                s.lancer { s.api.send(id, t, admin); cle++ }
            }, modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
        }
    }
}

// =========================================================
// OUTILS : email temporaire et numéros SMS
// =========================================================
private val PAYS = mapOf("BJ" to "🇧🇯 Bénin", "TG" to "🇹🇬 Togo", "CI" to "🇨🇮 Côte d'Ivoire", "SN" to "🇸🇳 Sénégal", "NG" to "🇳🇬 Nigeria", "GH" to "🇬🇭 Ghana", "CM" to "🇨🇲 Cameroun", "BF" to "🇧🇫 Burkina Faso", "KE" to "🇰🇪 Kenya")
private val CODE = Regex("\\b(\\d{3}[- ]?\\d{3}|\\d{4,8})\\b")

@Composable
fun Outils(s: AppState) {
    var onglet by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = onglet) {
            Tab(selected = onglet == 0, onClick = { onglet = 0 }, text = { Text("Email temporaire") }, icon = { Icon(Icons.Outlined.Mail, null) })
            Tab(selected = onglet == 1, onClick = { onglet = 1 }, text = { Text("Numéros SMS") }, icon = { Icon(Icons.Outlined.PhoneAndroid, null) })
        }
        if (onglet == 0) EmailTemporaire(s) else NumerosSms(s)
    }
}

@Composable
fun EmailTemporaire(s: AppState) {
    val presse = LocalClipboardManager.current; val cs = MaterialTheme.colorScheme
    var boite by remember { mutableStateOf(s.store.getMailbox()?.takeIf { b -> parseIso(b.expiresAt)?.time?.let { it > System.currentTimeMillis() } == true }) }
    var mails by remember { mutableStateOf<List<Mail>>(emptyList()) }
    var ouvert by remember { mutableStateOf<Mail?>(null) }
    var cle by remember { mutableIntStateOf(0) }
    LaunchedEffect(cle) {
        if (boite == null || cle > 0) {
            try { val b = s.api.createMailbox(); boite = b; s.store.setMailbox(b); mails = emptyList() } catch (e: Exception) { s.toast(e.message ?: "Erreur") }
        }
        while (true) {
            boite?.let { b -> try { mails = s.api.mails(b) } catch (_: Exception) { } }
            delay(5000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CarteSection {
                Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(boite?.address ?: "…", style = Mono.copy(fontSize = 17.sp), modifier = Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { boite?.let { presse.setText(AnnotatedString(it.address)); s.toast("Adresse copiée") } }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copier")
                    }
                    OutlinedButton(onClick = { cle++ }) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Nouvelle") }
                }
                boite?.let { b -> Text("Expire à ${heure(b.expiresAt)}, puis tout est effacé", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        item { Text("Boîte de réception (${mails.size})", style = MaterialTheme.typography.titleMedium) }
        if (mails.isEmpty()) item { Text("Aucun mail pour l'instant : ils apparaissent ici tout seuls.", color = cs.onSurfaceVariant) }
        items(mails, key = { it.id }) { m ->
            Card(onClick = { ouvert = m }, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(m.subject.ifBlank { "(sans objet)" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.from, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    ouvert?.let { m ->
        val texte = if (m.html.isNotBlank()) HtmlCompat.fromHtml(m.html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else m.text
        AlertDialog(
            onDismissRequest = { ouvert = null },
            title = { Text(m.subject.ifBlank { "(sans objet)" }) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(m.from, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); Text(texte) } },
            confirmButton = {
                val code = CODE.find(texte)?.value?.filter { it.isDigit() }
                if (code != null) TextButton(onClick = { presse.setText(AnnotatedString(code)); s.toast("Code copié : $code") }) { Text("Copier le code") }
            },
            dismissButton = { TextButton(onClick = { ouvert = null }) { Text("Fermer") } },
        )
    }
}

@Composable
fun NumerosSms(s: AppState) {
    val presse = LocalClipboardManager.current; val cs = MaterialTheme.colorScheme
    var numeros by remember { mutableStateOf<List<Phone>?>(null) }
    var choisi by remember { mutableStateOf<Phone?>(null) }
    var sms by remember { mutableStateOf<List<Sms>>(emptyList()) }
    LaunchedEffect(Unit) { numeros = try { s.api.phones() } catch (e: Exception) { s.toast(e.message ?: "Erreur"); emptyList() } }
    LaunchedEffect(choisi?.id) {
        val p = choisi ?: return@LaunchedEffect
        sms = emptyList()
        while (true) { try { sms = s.api.sms(p.id) } catch (_: Exception) { }; delay(8000) }
    }
    BackHandler(enabled = choisi != null) { choisi = null }
    val p = choisi
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (p == null) {
            item { Text("Numéros publics : tout le monde voit les SMS reçus. N'utilise jamais un numéro public pour un compte important.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            val l = numeros
            if (l == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else items(l, key = { it.id }) { n ->
                Card(onClick = { choisi = n }, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.e164, style = Mono.copy(fontSize = 16.sp))
                            Text("${PAYS[n.country] ?: n.country} · ${n.sms24h} SMS" + (n.lastSmsAt?.let { " · ${ilYa(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { presse.setText(AnnotatedString(n.e164)); s.toast("Numéro copié") }) { Icon(Icons.Outlined.ContentCopy, "Copier") }
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { choisi = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    Column(Modifier.weight(1f)) { Text(p.e164, style = Mono.copy(fontSize = 18.sp)); Text(PAYS[p.country] ?: p.country, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    FilledTonalButton(onClick = { presse.setText(AnnotatedString(p.e164)); s.toast("Numéro copié") }) { Text("Copier") }
                }
            }
            if (sms.isEmpty()) item { Text("En attente de SMS… Envoie ton code sur ce numéro.", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            items(sms) { m ->
                val code = CODE.find(m.body)?.value?.filter { it.isDigit() }
                Card(onClick = { if (code != null) { presse.setText(AnnotatedString(code)); s.toast("Code copié : $code") } },
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row { Text(m.sender, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(ilYa(m.receivedAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                        Text(m.body)
                        if (code != null) Text("Touche pour copier le code $code", style = MaterialTheme.typography.bodySmall, color = cs.tertiary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// =========================================================
// COMPTE : connexion par code reçu par email
// =========================================================
@Composable
fun Compte(s: AppState) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var etape by remember { mutableIntStateOf(1) }
    var envoi by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BarreRetour(s, if (s.session == null) "Connexion" else "Mon compte")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).imePadding()) {
            if (s.session == null) {
                Text("Sans mot de passe", style = MaterialTheme.typography.headlineSmall)
                Text("On t'envoie un code à 6 chiffres par email. Le même compte marche sur le site et dans l'application.", color = cs.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                if (etape == 1) {
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Ton email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !envoi, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        if (!Regex("^\\S+@\\S+\\.\\S+$").matches(email)) { s.toast("Email invalide"); return@Button }
                        envoi = true
                        s.lancer { try { s.api.sendCode(email.lowercase()); etape = 2; s.toast(if (s.api.demo) "Démo : tape n'importe quel code" else "Code envoyé à $email") } finally { envoi = false } }
                    }) { Text("Recevoir mon code") }
                } else {
                    OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("Code à 6 chiffres") }, singleLine = true,
                        textStyle = Mono.copy(fontSize = 24.sp, letterSpacing = 8.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !envoi, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        if (code.length < 6) { s.toast("Le code a 6 chiffres"); return@Button }
                        envoi = true
                        s.lancer {
                            try {
                                s.session = s.api.verifyCode(email.lowercase(), code)
                                s.rafraichirMoi(); s.toast("Bienvenue !"); s.retour()
                            } finally { envoi = false }
                        }
                    }) { Text("Me connecter") }
                    TextButton(onClick = { etape = 1; code = "" }, modifier = Modifier.fillMaxWidth()) { Text("Changer d'email") }
                }
            } else {
                CarteSection {
                    Text(s.me.email ?: s.session?.email ?: "", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Pack outils : " + when (s.me.plan) { "bronze" -> "Bronze"; "argent" -> "Argent"; "or" -> "Or"; else -> "Gratuit" }, color = cs.onSurfaceVariant)
                    if (s.me.plan != "free") Text("Locations de numéros restantes : ${s.me.credits}", color = cs.onSurfaceVariant)
                    if (s.me.role == "admin") { Spacer(Modifier.height(8.dp)); Surface(color = cs.tertiaryContainer, shape = CircleShape) { Text("Administrateur", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = cs.onTertiaryContainer, style = MaterialTheme.typography.labelMedium) } }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { s.onglet(Ecran.Commandes) }, modifier = Modifier.fillMaxWidth()) { Text("Mes commandes") }
                TextButton(onClick = { s.deconnecter(); s.toast("Déconnecté") }, modifier = Modifier.fillMaxWidth()) { Text("Se déconnecter") }
            }
        }
    }
}
