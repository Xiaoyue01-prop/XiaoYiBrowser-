package com.example.webedge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private, explicit SAF workflow. No key or plaintext is saved in an Intent, Bundle or file. */
class RecoveryArchiveActivity : BiometricBrowserActivity() {
    private val restoreMode get() = intent.getBooleanExtra(EXTRA_RESTORE, false)
    private var recoveryKey: String? = null
    private var pendingUri: Uri? = null
    private var busy = false
    private var pickerOpen = false
    private var restored = false
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var exit: Button
    private lateinit var keyInput: EditText
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        acceptPickerResult(uri)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        acceptPickerResult(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Independently owned secure window; the existing app lock must not clear this flag.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        startProtectedBrowser { render() }
    }

    override fun onPostResume() {
        super.onPostResume()
        setRecentsScreenshotEnabled(false)
        window.decorView.post { drainResult() }
    }

    override fun onBrowserUnlocked() = drainResult()

    private fun acceptPickerResult(uri: Uri?) {
        // Activity Result Registry can redeliver after process death. Never silently use a newly generated key.
        if (!pickerOpen) return
        pickerOpen = false
        // SAF documents only; never read or truncate file:// or an arbitrary non-document URI.
        if (uri != null && (uri.scheme != ContentResolver.SCHEME_CONTENT ||
                !DocumentsContract.isDocumentUri(this, uri))) {
            if (::status.isInitialized) status.text = getString(R.string.recovery_picker_error)
            return
        }
        pendingUri = uri
        drainResult()
    }

    private fun render() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(page)
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; isSaveEnabled = false
        }.also { page.addView(it) }
        fun button(value: String, click: () -> Unit) = Button(this).apply {
            text = value; isAllCaps = false; isSaveEnabled = false
            setOnClickListener { if (!deferBrowserAction() && !busy && !pickerOpen && pendingUri == null) click() }
        }.also { page.addView(it) }
        label(if (restoreMode) getString(R.string.recovery_restore_title) else getString(R.string.recovery_export_title), 25f)
        if (restoreMode) {
            label(getString(R.string.recovery_restore_intro), 15f)
            keyInput = EditText(this).apply {
                hint = getString(R.string.recovery_key_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true; isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }.also { page.addView(it) }
            action = button(getString(R.string.recovery_select_file)) {
                if (keyInput.text.toString().trim().length != 43) { status.text = getString(R.string.recovery_invalid_key); return@button }
                pickerOpen = true
                try { openDocument.launch(arrayOf("*/*")) }
                catch (_: Exception) { pickerOpen = false; status.text = getString(R.string.recovery_picker_error) }
            }
        } else {
            label(getString(R.string.recovery_export_intro), 15f)
            val generated = RecoveryArchive.newKey()
            recoveryKey = generated
            label(getString(R.string.recovery_key_warning), 15f)
            label(generated, 18f)
            button(getString(R.string.recovery_copy_key)) {
                val clip = ClipData.newPlainText("Summit recovery key", generated)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                try {
                    checkNotNull(getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
                    status.text = getString(R.string.recovery_key_copied)
                } catch (_: Exception) { status.text = getString(R.string.recovery_copy_error) }
            }
            action = button(getString(R.string.recovery_create_file)) {
                pickerOpen = true
                try { createDocument.launch("Summit-recovery-${System.currentTimeMillis()}.summit") }
                catch (_: Exception) { pickerOpen = false; status.text = getString(R.string.recovery_picker_error) }
            }
        }
        status = label("", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        exit = button(if (restoreMode) getString(R.string.recovery_skip) else getString(R.string.recovery_close)) {
            if (restoreMode) continueToBrowser() else finish()
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun drainResult() {
        if (!::status.isInitialized || busy || deferBrowserAction() || isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val uri = pendingUri ?: return
        pendingUri = null
        val key = if (restoreMode) keyInput.text.toString().trim() else recoveryKey
        if (key.isNullOrBlank()) { status.text = getString(R.string.recovery_session_expired); return }
        busy = true
        action.isEnabled = false; exit.isEnabled = false
        status.text = if (restoreMode) getString(R.string.recovery_restoring) else getString(R.string.recovery_exporting)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (restoreMode) {
                        val archive = checkNotNull(contentResolver.openInputStream(uri)).use(RecoveryArchive::readBounded)
                        val entries = RecoveryArchive.open(archive, key)
                        RecoverySnapshotValidation.validate(entries)
                        // No cache, sync or WebView restore may have started on the destination.
                        Store.restoreBeforeStore(applicationContext, entries)
                    } else {
                        val store = Store.get(applicationContext)
                        val entries = ProtectedRecords.snapshotForRecovery(applicationContext, store::syncHealthy)
                        RecoverySnapshotValidation.validate(entries)
                        val archive = RecoveryArchive.seal(entries, key)
                        checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(archive); it.flush() }
                        val actual = checkNotNull(contentResolver.openInputStream(uri)).use(RecoveryArchive::readBounded)
                        check(actual.contentEquals(archive) && RecoveryArchive.open(actual, key) == entries)
                    }
                }
                if (restoreMode) {
                    restored = true
                    keyInput.text.clear()
                    status.text = getString(R.string.recovery_restore_success)
                    exit.text = getString(R.string.recovery_open_browser)
                } else status.text = getString(R.string.recovery_export_success)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.text = if (restoreMode) getString(R.string.recovery_restore_error)
                    else getString(R.string.recovery_export_error)
            } finally {
                busy = false
                action.isEnabled = !restored
                exit.isEnabled = true
            }
        }
    }

    private fun continueToBrowser() {
        // Only an explicit skip or successful import dismisses the fresh-install offer.
        if (!getSharedPreferences("edge", MODE_PRIVATE).edit().putBoolean(SEEN, true).commit()) {
            status.text = getString(R.string.recovery_setup_error)
            return
        }
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        finish()
    }

    override fun onDestroy() {
        recoveryKey = null
        pendingUri = null
        if (::keyInput.isInitialized) keyInput.text.clear()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_RESTORE = "recovery_restore"
        // edge.xml is excluded from Android cloud backup and device transfer.
        private const val SEEN = "recovery_setup_seen_v1"
        internal fun shouldOffer(context: Context): Boolean =
            !context.getSharedPreferences("edge", Context.MODE_PRIVATE).getBoolean(SEEN, false) &&
                Store.unusedForRecovery() && ProtectedRecords.canRestoreArchive(context)
    }
}
