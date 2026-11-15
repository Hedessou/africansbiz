package com.africansbiz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SessionStore(this)
        // Sans clés Supabase (secrets GitHub), l'application démarre en mode démo
        val api: Backend = if (BuildConfig.SUPABASE_URL.isBlank()) DemoBackend()
        else RealBackend(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, store)
        setContent { AfricansBizTheme { App(api, store) } }
    }
}
