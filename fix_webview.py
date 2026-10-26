import textwrap

MAIN = 'app/src/main/java/com/example/webedge/MainActivity.kt'
MANIFEST = 'app/src/main/AndroidManifest.xml'
STORE = 'app/src/main/java/com/example/webedge/Store.kt'
report = []

def read(p):
    with open(p, 'r', encoding='utf-8') as f: return f.read()
def write(p, c):
    with open(p, 'w', encoding='utf-8') as f: f.write(c)
def indent_of(content, marker):
    for line in content.split('\n'):
        if marker in line:
            return line[:len(line)-len(line.lstrip())]
    return '    '

m = read(MANIFEST)
if 'android.permission.CAMERA' not in m:
    mind = indent_of(m, 'android.permission.INTERNET')
    perms = '\n'.join([
        mind + '<uses-permission android:name="android.permission.CAMERA" />',
        mind + '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        mind + '<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
        mind + '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
        mind + '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
    ])
    lines = m.split('\n')
    for i, l in enumerate(lines):
        if 'android.permission.INTERNET' in l:
            lines.insert(i + 1, perms)
            break
    m = '\n'.join(lines)
    report.append('[OK] Manifest: them quyen camera/mic/vi tri')
if 'usesCleartextTraffic="false"' in m:
    m = m.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"')
    report.append('[OK] Manifest: cho phep trang HTTP')
write(MANIFEST, m)

s = read(STORE)
old_h = 'if (url.isBlank() || url.startsWith("about:")) return'
new_h = 'if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return'
if old_h in s:
    write(STORE, s.replace(old_h, new_h))
    report.append('[OK] Store: bo qua trang loi trong lich su')

c = read(MAIN)
if 'import android.webkit.WebResourceRequest' not in c:
    imp = '\n'.join([
        'import android.webkit.WebResourceError',
        'import android.webkit.WebResourceRequest',
        'import android.webkit.WebResourceResponse',
        'import android.webkit.WebSettings',
    ])
    c = c.replace('import android.webkit.WebViewClient',
                  'import android.webkit.WebViewClient\n' + imp, 1)
    report.append('[OK] Import: WebResource*/WebSettings')

if 'mixedContentMode' not in c:
    a = 'ws.domStorageEnabled = true'
    ind = indent_of(c, a)
    c = c.replace(a, a + '\n' + ind + 'ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE', 1)
    report.append('[OK] Bat mixedContentMode')

if 'setAcceptThirdPartyCookies' not in c:
    a = 'web.webChromeClient = chromeClient'
    ind = indent_of(c, a)
    add = ind + 'CookieManager.getInstance().setAcceptCookie(true)\n' + \
          ind + 'CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)'
    c = c.replace(a, a + '\n' + add, 1)
    report.append('[OK] Bat cookie ben thu ba (fix dang nhap OAuth)')

# GIU NGUYEN web.setBackgroundColor(Color.WHITE) theo yeu cau

if 'lastFailedUrl' not in c:
    a = 'private var currentUrl = ""'
    ind = indent_of(c, a)
    c = c.replace(a, a + '\n' + ind + 'private var lastFailedUrl: String? = null', 1)

marker = 'if (!url.startsWith("data:")) lastFailedUrl = null'
if marker not in c:
    a = 'web.alpha = 0.6f'
    ind = indent_of(c, a)
    c = c.replace(a, a + '\n' + ind + marker, 1)

c = c.replace('reloadBtn.setOnClickListener { web.reload() }',
              'reloadBtn.setOnClickListener { reloadOrRetry() }')
c = c.replace('cell(quick, "Tải lại", Icons.REFRESH) { web.reload() }',
              'cell(quick, "Tải lại", Icons.REFRESH) { reloadOrRetry() }')

helpers = '''
private fun reloadOrRetry() {
    val failed = lastFailedUrl
    if (failed != null && (web.url ?: "").startsWith("data:")) {
        lastFailedUrl = null
        web.loadUrl(failed)
    } else {
        web.reload()
    }
}

private fun openExternal(intent: Intent, errMsg: String) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        toast(errMsg)
    }
}

private fun handleIntentUrl(url: String) {
    try {
        startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
    } catch (e: Exception) {
        val pkg = url.substringAfter("package=").substringBefore("&").substringBefore(";").substringBefore("#")
        if (pkg.isNotBlank() && pkg.contains(".")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))
            } catch (e2: Exception) {
                toast("Không mở được liên kết này")
            }
        } else {
            toast("Không mở được liên kết này")
        }
    }
}

private fun showErrorPage(view: WebView, msg: String) {
    val bg = if (isNight) "#121212" else "#ffffff"
    val tx = if (isNight) "#e8e8e8" else "#1a1a1a"
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0;background:$bg;color:$tx;text-align:center}
        .box{padding:24px;max-width:320px}
        h2{font-size:19px;margin:0 0 8px}
        p{font-size:14px;opacity:.65;margin:0 0 6px;line-height:1.4}
        </style>
        </head>
        <body>
        <div class="box">
        <h2>Không thể tải trang</h2>
        <p>$msg</p>
        <p>Kiểm tra kết nối mạng, sau đó bấm nút tải lại.</p>
        </div>
        </body>
        </html>
    """.trimIndent()
    view.loadUrl("data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8"))
}
'''
if 'private fun showErrorPage' not in c:
    class_ind = indent_of(c, 'private fun dp(v: Int): Int =')
    block = textwrap.indent(helpers.strip('\n'), class_ind)
    client_anchor = 'private val client = object : WebViewClient() {'
    c = c.replace(client_anchor, block + '\n\n' + class_ind + client_anchor, 1)
    report.append('[OK] Them helper: showErrorPage/reloadOrRetry/openExternal/handleIntentUrl')

overrides = '''
override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    val uri = request.url
    val scheme = uri.scheme ?: return false
    return when (scheme) {
        "http", "https" -> false
        "about", "data", "file", "javascript", "blob" -> false
        "mailto" -> {
            openExternal(Intent(Intent.ACTION_SENDTO, uri), "Không mở được ứng dụng email")
            true
        }
        "tel" -> {
            openExternal(Intent(Intent.ACTION_DIAL, uri), "Không mở được trình quay số")
            true
        }
        "sms", "smsto" -> {
            openExternal(Intent(Intent.ACTION_SENDTO, uri), "Không mở được ứng dụng tin nhắn")
            true
        }
        "intent" -> {
            handleIntentUrl(uri.toString())
            true
        }
        "market" -> {
            openExternal(Intent(Intent.ACTION_VIEW, uri), "Không mở được Play Store")
            true
        }
        else -> {
            // Scheme riêng của app (snssdk1180:// TikTok, zalo://, fb://, ...)
            val opened = try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: Exception) {
                false
            }
            if (!opened && request.hasGesture()) {
                toast("Máy chưa cài ứng dụng để mở liên kết này")
            }
            true
        }
    }
}

override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
    super.onReceivedError(view, request, error)
    if (request.isForMainFrame) {
        lastFailedUrl = request.url.toString()
        showErrorPage(view, error.description?.toString() ?: "Không thể tải trang")
    }
}

override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
    super.onReceivedHttpError(view, request, errorResponse)
    if (request.isForMainFrame && errorResponse.statusCode >= 500) {
        lastFailedUrl = request.url.toString()
        showErrorPage(view, "Máy chủ gặp sự cố (" + errorResponse.statusCode + ")")
    }
}
'''
if 'override fun shouldOverrideUrlLoading' not in c:
    member_ind = indent_of(c, 'override fun onPageFinished(view: WebView, url: String) {')
    lines = c.split('\n')
    chrome_idx = None
    for i, l in enumerate(lines):
        if 'private val chromeClient = object : WebChromeClient() {' in l:
            chrome_idx = i
            break
    if chrome_idx is not None:
        j = chrome_idx - 1
        while j >= 0 and lines[j].strip() == '':
            j -= 1
        if lines[j].strip() == '}':
            block = textwrap.indent(overrides.strip('\n'), member_ind).split('\n')
            lines[j:j] = block + ['']
            c = '\n'.join(lines)
            report.append('[OK] Them overrides: shouldOverrideUrlLoading/onReceivedError/onReceivedHttpError')
        else:
            report.append('[WARN] Khong tim thay closing brace cua client')
    else:
        report.append('[WARN] Khong tim thay chromeClient')

write(MAIN, c)

print('='*50)
print('KET QUA')
print('='*50)
for r in report: print(r)
final = read(MAIN)
print('--- VERIFY ---')
print('shouldOverrideUrlLoading :', 'shouldOverrideUrlLoading' in final)
print('onReceivedError          :', 'onReceivedError' in final)
print('cookies                  :', 'setAcceptThirdPartyCookies' in final)
print('WebView van mau trang    :', 'web.setBackgroundColor(Color.WHITE)' in final)
