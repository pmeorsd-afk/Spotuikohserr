package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.preferences.MusicSource
import com.music.spotui.data.preferences.setPrimaryMusicSource
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val SPOTIFY_GREEN = 0xFF1ED760

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf("") }

    val pageReady = remember { AtomicBoolean(false) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // The off-screen full-size playback WebView is attached above Compose. Remove it
    // while login is visible or it can intercept/draw over this WebView.
    LaunchedEffect(Unit) {
        com.music.spotui.di.SpotifyWebPlayer.detach()
    }

    val navigateToHome: () -> Unit = {
        if (SpotifySession.spDc(context) != "anonymous") {
            com.music.spotui.di.SpotifyWebPlayer.attach(context as Activity)
        }
        com.music.spotui.data.preferences.setPrimaryMusicSource(context, MusicSource.YOUTUBE_MUSIC)
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.Login.route) { inclusive = true }
        }
    }

    // Poll for the sp_dc cookie across Spotify domains
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (tokenFetchStarted.get()) continue
            val spDc = extractCookie("sp_dc")
            if (!spDc.isNullOrBlank() && tokenFetchStarted.compareAndSet(false, true)) {
                finishLoginWithToken(
                    spDc = spDc,
                    spKey = extractCookie("sp_key") ?: "",
                    view = webViewRef,
                    activity = context as Activity,
                    scope = scope,
                    setProcessing = { isProcessing = it },
                    setStatus = { statusMessage = it },
                    setError = { hasError = it },
                    tokenFetchStarted = tokenFetchStarted,
                    onSuccess = navigateToHome,
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Full-screen WebView configured to work through filtered devices and custom CAs
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 44.dp, bottom = 96.dp),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.removeAllCookies(null)
                cookieManager.flush()

                WebView(ctx).apply {
                    webViewRef = this
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true

                    webChromeClient = object : WebChromeClient() {}

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageReady.set(false)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady.set(true)
                            view?.let(::fixSpotifyLoginLayout)
                        }
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            // Allows protected devices / kosher filter proxy certificates to connect
                            handler?.proceed()
                        }
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            return false
                        }
                    }
                    loadUrl(SpotifyAuth.LOGIN_URL)
                }
            },
        )

        // Slim top bar: title, status message
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(44.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isProcessing) statusMessage.ifBlank { "Signing in…" } else "Log in to Spotify",
                color = if (hasError) Color(0xFFE22134) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        // Bottom action bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF121212))
                .padding(vertical = 8.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    isProcessing = true
                    statusMessage = "מתחבר כאורח…"
                    SpotifySession.setSpDc(context, "anonymous")
                    setPrimaryMusicSource(context, MusicSource.YOUTUBE_MUSIC)
                    scope.launch(Dispatchers.IO) {
                        SpotifyTokenProvider.ensureToken(context)
                        withContext(Dispatchers.Main) {
                            navController.navigate(Routes.Home.route) {
                                popUpTo(Routes.Login.route) { inclusive = true }
                            }
                        }
                    }
                },
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF282828),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text(
                    text = "כניסה ללא חשבון",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(2.dp))

            TextButton(
                onClick = { showTokenDialog = true },
                enabled = !isProcessing,
            ) {
                Text(
                    text = "מתקשה להתחבר? לחץ להתחברות עם טוקן (sp_dc)",
                    color = Color(SPOTIFY_GREEN),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showTokenDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("התחברות ידנית (sp_dc Cookie)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        "אם הסינון במכשיר חוסם את טופס האימות, ניתן להעתיק את ה-Cookie שנקרא sp_dc מהדפדפן במחשב (מ-open.spotify.com) ולהדביק אותו כאן:",
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        singleLine = false,
                        maxLines = 4,
                        placeholder = { Text("הדבק כאן את ערך ה-sp_dc...", color = Color.Gray, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(SPOTIFY_GREEN),
                            unfocusedBorderColor = Color.Gray,
                            focusedContainerColor = Color(0xFF121212),
                            unfocusedContainerColor = Color(0xFF121212),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val token = manualTokenInput.trim()
                        if (token.isNotBlank() && tokenFetchStarted.compareAndSet(false, true)) {
                            showTokenDialog = false
                            finishLoginWithToken(
                                spDc = token,
                                spKey = "",
                                view = webViewRef,
                                activity = context as Activity,
                                scope = scope,
                                setProcessing = { isProcessing = it },
                                setStatus = { statusMessage = it },
                                setError = { hasError = it },
                                tokenFetchStarted = tokenFetchStarted,
                                onSuccess = navigateToHome,
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(SPOTIFY_GREEN), contentColor = Color.Black),
                    enabled = manualTokenInput.isNotBlank() && !isProcessing,
                ) {
                    Text("התחבר", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTokenDialog = false },
                    enabled = !isProcessing,
                ) {
                    Text("ביטול", color = Color.White)
                }
            }
        )
    }
}

/** Spotify sometimes computes its login <main> at ~48px tall inside WebView. */
private fun fixSpotifyLoginLayout(webView: WebView) {
    webView.post {
        val viewportHeight = webView.height.coerceAtLeast(1)
        webView.evaluateJavascript(
            """
            (function(){
              var style = document.getElementById('spotui-login-layout-fix');
              if (!style) {
                style = document.createElement('style');
                style.id = 'spotui-login-layout-fix';
                document.head.appendChild(style);
              }
              style.textContent = 'html,body,#__next{height:${viewportHeight}px!important;min-height:${viewportHeight}px!important;}' +
                'main{height:${viewportHeight}px!important;min-height:${viewportHeight}px!important;max-height:none!important;position:relative!important;overflow:auto!important;}';
            })();
            """.trimIndent(),
            null,
        )
    }
}

private fun extractCookie(name: String): String? {
    val cookieManager = CookieManager.getInstance()
    val domains = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://spotify.com",
        "https://www.spotify.com",
        "https://.spotify.com",
    )
    for (url in domains) {
        val allCookies = cookieManager.getCookie(url) ?: continue
        val match = allCookies.split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .firstOrNull { it.first == name && it.second.isNotBlank() }
            ?.second
        if (!match.isNullOrBlank()) return match
    }
    return null
}

private fun finishLoginWithToken(
    spDc: String,
    spKey: String = "",
    view: WebView?,
    activity: Activity,
    scope: kotlinx.coroutines.CoroutineScope,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
    onSuccess: () -> Unit,
) {
    if (spDc.isBlank()) {
        setProcessing(true)
        setError(true)
        setStatus("Couldn't read login cookie. Please try again.")
        tokenFetchStarted.set(false)
        return
    }

    setProcessing(true)
    setError(false)
    setStatus("Connecting…")
    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        SpotifySession.setSpDc(activity, spDc)
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = SpotifyAuth.fetchAccessToken(spDc, spKey)
            result.onSuccess { token ->
                Spotify.accessToken = token.accessToken
                withContext(Dispatchers.Main) { setStatus("Success!") }
                delay(300)
                withContext(Dispatchers.Main) { onSuccess() }
                return@launch
            }.onFailure { e ->
                lastError = e
                Timber.e(e, "Spotify token fetch failed (attempt ${attempt + 1})")
                if (attempt < 2) delay(800)
            }
        }
        withContext(Dispatchers.Main) {
            setStatus("Login failed: ${lastError?.message ?: "unknown error"}")
            setError(true)
        }
        tokenFetchStarted.set(false)
    }
}
