package com.example.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    // Force Hebrew RTL layout direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        val currentUrl by viewModel.currentTabUrl.collectAsState()
        val blockedUrl by viewModel.blockedUrl.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val loadProgress by viewModel.loadProgress.collectAsState()
        val navigationTrigger by viewModel.navigationTrigger.collectAsState()

        // WebView reference
        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        // Context and states for floating control bar
        val ctx = LocalContext.current
        var isFloatingBarVisible by remember { mutableStateOf(false) }
        var isSettingsDialogOpen by remember { mutableStateOf(false) }

        LaunchedEffect(isFloatingBarVisible) {
            if (isFloatingBarVisible) {
                kotlinx.coroutines.delay(6000)
                isFloatingBarVisible = false
            }
        }

        // File upload callback state
        var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

        // Activity Result Launcher for File Chooser (Upload support)
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val resultUri = if (result.resultCode == Activity.RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    val dataString = data.dataString
                    if (dataString != null) {
                        arrayOf(Uri.parse(dataString))
                    } else {
                        null
                    }
                }
            } else {
                null
            }
            fileChooserCallback?.onReceiveValue(resultUri)
            fileChooserCallback = null
        }

        // Back press handling: System back button performs browser back navigation or exits the app if no history
        val canGoBack = webViewRef?.canGoBack() == true
        val isBlocked = blockedUrl != null
        BackHandler(enabled = isBlocked || canGoBack) {
            if (isBlocked) {
                viewModel.clearBlockedPage()
                if (webViewRef?.canGoBack() == true) {
                    webViewRef?.goBack()
                } else {
                    viewModel.loadUrl(BrowserConfig.INITIAL_URL)
                }
            } else if (canGoBack) {
                webViewRef?.goBack()
            }
        }

        // Navigation Trigger Listener
        LaunchedEffect(navigationTrigger) {
            navigationTrigger?.let { url ->
                webViewRef?.loadUrl(url)
                viewModel.clearNavigationTrigger()
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val blockedUrlVal = blockedUrl
            if (blockedUrlVal != null) {
                // Beautiful Hebrew restriction warning screen (scrollable to prevent long URLs from pushing content offscreen)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                        .testTag("blocked_screen"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "אתר חסום",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "הגישה לאתר חסומה",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "הדפדפן מוגדר לאפשר גלישה רק בדומיינים מאושרים בקוד.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "הכתובת המבוקשת:\n$blockedUrlVal",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.clearBlockedPage()
                                viewModel.loadUrl(BrowserConfig.INITIAL_URL)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "דף הבית"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "דף הבית",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.clearBlockedPage()
                                if (webViewRef?.canGoBack() == true) {
                                    webViewRef?.goBack()
                                } else {
                                    viewModel.loadUrl(BrowserConfig.INITIAL_URL)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "סגור"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "סגור",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            } else {
                // Fully configured hardware-accelerated WebView inside a SwipeRefreshLayout for Pull-to-Refresh
                AndroidView(
                    factory = { ctx ->
                        val webView = WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                allowContentAccess = true
                                allowFileAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (url != null) {
                                        if (!BrowserConfig.isUrlAllowed(url)) {
                                            view?.stopLoading()
                                            viewModel.showBlockedPage(url)
                                            return
                                        }
                                    }
                                    viewModel.setLoading(true)
                                    if (url != null) {
                                        viewModel.updateActiveTabUrl(url)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    viewModel.setLoading(false)
                                    (view?.parent as? androidx.swiperefreshlayout.widget.SwipeRefreshLayout)?.isRefreshing = false
                                    if (url != null) {
                                        viewModel.updateActiveTabUrl(url)
                                        view?.title?.let { title ->
                                            viewModel.updateActiveTabTitle(title)
                                            viewModel.addToHistory(title, url)
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val urlStr = request?.url?.toString() ?: return false
                                    if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                                        if (!BrowserConfig.isUrlAllowed(urlStr)) {
                                            viewModel.showBlockedPage(urlStr)
                                            return true
                                        }
                                        return false
                                    }
                                    return true
                                }

                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler?.proceed()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    viewModel.setLoadProgress(newProgress)
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    super.onReceivedTitle(view, title)
                                    if (title != null) {
                                        viewModel.updateActiveTabTitle(title)
                                        view?.url?.let { url ->
                                            viewModel.addToHistory(title, url)
                                        }
                                    }
                                }

                                // Handle File Chooser for File Upload support
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    fileChooserCallback?.onReceiveValue(null)
                                    fileChooserCallback = filePathCallback

                                    try {
                                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        }
                                        fileChooserLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        filePathCallback?.onReceiveValue(null)
                                        fileChooserCallback = null
                                        Toast.makeText(ctx, "שגיאה בפתיחת בחירת קבצים", Toast.LENGTH_SHORT).show()
                                    }
                                    return true
                                }
                            }

                            // Handle File Downloads support using DownloadManager
                            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                try {
                                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                                        setMimeType(mimetype)
                                        addRequestHeader("User-Agent", userAgent)
                                        val cookies = CookieManager.getInstance().getCookie(url)
                                        if (!cookies.isNullOrEmpty()) {
                                            addRequestHeader("Cookie", cookies)
                                        }
                                        setDescription("מוריד קובץ...")
                                        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                        setTitle(filename)
                                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                    }
                                    val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    downloadManager.enqueue(request)
                                    Toast.makeText(ctx, "הורדת הקובץ החלה", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        ctx.startActivity(intent)
                                    } catch (ex: Exception) {
                                        Toast.makeText(ctx, "שגיאה בהורדת הקובץ", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }

                            webViewRef = this
                            if (currentUrl.isNotEmpty() && currentUrl != "about:blank") {
                                loadUrl(currentUrl)
                            }
                        }

                        val swipeRefreshLayout = object : androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx) {
                            override fun canChildScrollUp(): Boolean {
                                return webView.canScrollVertically(-1)
                            }
                        }.apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            addView(webView)
                            setOnRefreshListener {
                                isRefreshing = false
                                isFloatingBarVisible = true
                            }

                            // Prevent SwipeRefreshLayout from intercepting scroll gestures on inner elements or floating screens
                            webView.setOnTouchListener { _, event ->
                                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                    isFloatingBarVisible = false
                                    if (isRefreshing) {
                                        isEnabled = true
                                    } else {
                                        val isAtTop = !webView.canScrollVertically(-1)
                                        val density = ctx.resources.displayMetrics.density
                                        val thresholdPx = 150 * density
                                        val isNearTop = event.y < thresholdPx
                                        isEnabled = isAtTop && isNearTop
                                    }
                                }
                                false
                            }
                        }
                        
                        swipeRefreshLayout
                    },
                    update = { swipeRefreshLayout ->
                        val webView = swipeRefreshLayout.getChildAt(0) as? WebView
                        webViewRef = webView
                        swipeRefreshLayout.isRefreshing = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // High priority minimal progress bar at the very top of the screen (no other toolbars shown)
            if (isLoading && blockedUrl == null) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            // Beautiful floating pill layout (סרגל צף) at the top
            AnimatedVisibility(
                visible = isFloatingBarVisible && blockedUrl == null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .zIndex(10f)
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(horizontal = 16.dp)
                        .testTag("floating_control_bar")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Back button (הדף הקודם)
                        val canBack = webViewRef?.canGoBack() == true
                        IconButton(
                            onClick = {
                                if (canBack) webViewRef?.goBack()
                            },
                            enabled = canBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "הדף הקודם",
                                tint = if (canBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // Forward button (הדף הבא)
                        val canForward = webViewRef?.canGoForward() == true
                        IconButton(
                            onClick = {
                                if (canForward) webViewRef?.goForward()
                            },
                            enabled = canForward
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "הדף הבא",
                                tint = if (canForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // Refresh button (רענון)
                        IconButton(
                            onClick = {
                                webViewRef?.reload()
                                isFloatingBarVisible = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "רענון"
                            )
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        // App Settings button (הגדרות האפליקציה)
                        IconButton(
                            onClick = {
                                isSettingsDialogOpen = true
                                isFloatingBarVisible = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "הגדרות האפליקציה"
                            )
                        }

                        // Close button (סגור סרגל)
                        IconButton(
                            onClick = {
                                isFloatingBarVisible = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "סגור סרגל"
                            )
                        }
                    }
                }
            }

            // App Settings Dialog
            if (isSettingsDialogOpen) {
                AlertDialog(
                    onDismissRequest = { isSettingsDialogOpen = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "הגדרות האפליקציה",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Section: App info / Allowed domains
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "אתרים מורשים לגלישה:",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "דפדפן זה מוגן ומאפשר גלישה רק באתרים הבאים:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    // Flow row of allowed domains
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        BrowserConfig.ALLOWED_DOMAINS.forEach { domain ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape)
                                                )
                                                Text(
                                                    text = domain,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Section: Actions
                            Text(
                                text = "פעולות ותחזוקה:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )

                            // Button: Clear History
                            Button(
                                onClick = {
                                    viewModel.clearHistory()
                                    Toast.makeText(ctx, "היסטוריית הגלישה נמחקה בהצלחה", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("מחיקת היסטוריית גלישה")
                            }

                            // Button: Clear Cache & Cookies
                            Button(
                                onClick = {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    webViewRef?.clearCache(true)
                                    Toast.makeText(ctx, "זיכרון המטמון והעוגיות נמחקו בהצלחה", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("ניקוי זיכרון מטמון ועוגיות")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { isSettingsDialogOpen = false }
                        ) {
                            Text("סגור", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }
}
