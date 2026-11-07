package com.tuempresa.videogenerator

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object NetworkConfig {
    const val DOMAIN = "api.tu-dominio.com"
    const val BASE_URL = "https://$DOMAIN"
    const val WS_URL = "wss://$DOMAIN/ws"
    const val SSL_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    fun getSecurePrefs(context: Context): EncryptedSharedPreferences {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "secure_token_prefs", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_GCM,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun buildOkHttpClient(context: Context): OkHttpClient {
        val prefs = getSecurePrefs(context)
        val pinner = CertificatePinner.Builder().add(DOMAIN, SSL_PIN).build()
        return OkHttpClient.Builder()
            .certificatePinner(pinner)
            .addInterceptor { chain ->
                val token = prefs.getString("jwt_token", "") ?: ""
                chain.proceed(chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token").build())
            }
            .authenticator(TokenAuthenticator(prefs))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class TokenAuthenticator(private val prefs: EncryptedSharedPreferences) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 401) return null
        if (response.request.header("X-Refresh-Attempt") != null) return null
        val newToken = fetchNewTokenSync() ?: return null
        prefs.edit().putString("jwt_token", newToken).apply()
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Refresh-Attempt", "true")
            .build()
    }
    private fun fetchNewTokenSync(): String? = null
}

class ComfyViewModel(private val client: OkHttpClient) : ViewModel() {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _status = MutableStateFlow("Listo")
    val status: StateFlow<String> = _status
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done
    fun start() { _done.value = false; _progress.value = 0f; _status.value = "Conectando..." }
    override fun onCleared() { super.onCleared() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val client = NetworkConfig.buildOkHttpClient(applicationContext)
        val vm = ComfyViewModel(client)
        setContent { MaterialTheme { MainScreen(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: ComfyViewModel) {
    val p by vm.progress.collectAsState()
    val st by vm.status.collectAsState()
    val dn by vm.done.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(st)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.start() }, enabled = !dn, Modifier.fillMaxWidth()) {
            Text("Generar Video con IA")
        }
    }
}
