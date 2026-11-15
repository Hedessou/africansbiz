package com.africansbiz.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions

// =========================================================
// PUBLICITÉ DANS L'APPLICATION
//  - Bannière d'un annonceur (payée via SasPay sur le site), sinon « Ton annonce ici »
//  - Vidéo récompensée AdMob : +24 h sur l'adresse email temporaire, vérifié par le serveur (fonction admob-reward)
//  Rien de tout cela n'est montré aux membres VIP, sauf la vidéo qu'on choisit de regarder.
// =========================================================
const val SITE_URL = "https://hedessou.github.io/africansbiz/"

private fun Context.activite(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activite()
    else -> null
}

@Composable
fun BanniereAnnonce(s: AppState, modifier: Modifier = Modifier) {
    if (s.me.vip) return
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var annonce by remember { mutableStateOf<Annonce?>(null) }
    LaunchedEffect(Unit) { annonce = try { s.api.annonce() } catch (_: Exception) { null } }
    val a = annonce
    OutlinedCard(
        onClick = { try { uri.openUri(a?.url ?: (SITE_URL + "#annonceurs")) } catch (_: Exception) { } },
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (a != null) {
                Text("ANNONCE", style = MaterialTheme.typography.labelSmall, color = cs.tertiary, letterSpacing = 1.5.sp)
                Text(a.titre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(a.texte, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            } else {
                Text("Ton annonce ici · dès ${francs(2500)} la semaine", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BoutonVideo(s: AppState, adresse: String?, modifier: Modifier = Modifier, onRecompense: () -> Unit) {
    val ctx = LocalContext.current
    var chargement by remember { mutableStateOf(false) }
    FilledTonalButton(
        enabled = !chargement && adresse != null,
        modifier = modifier.fillMaxWidth().height(52.dp),
        onClick = {
            val act = ctx.activite() ?: return@FilledTonalButton
            chargement = true
            // L'adresse à prolonger part dans custom_data ; le serveur la reçoit dans la confirmation signée de Google
            val options = ServerSideVerificationOptions.Builder()
                .setUserId(s.session?.userId() ?: "")
                .setCustomData(adresse ?: "")
                .build()
            RewardedAd.load(ctx, BuildConfig.ADMOB_REWARDED, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    chargement = false
                    ad.setServerSideVerificationOptions(options)
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdFailedToShowFullScreenContent(e: AdError) { s.toast("La vidéo n'a pas pu s'afficher, réessaie") }
                    }
                    ad.show(act) { _ ->
                        s.toast("Merci ! Ton adresse est prolongée de 24 h")
                        onRecompense()
                    }
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    chargement = false
                    s.toast("Aucune vidéo disponible pour le moment, réessaie plus tard")
                }
            })
        },
    ) {
        if (chargement) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else { Icon(Icons.Outlined.PlayCircle, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Regarder une vidéo · +24 h") }
    }
}
