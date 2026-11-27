"""Static contracts; not a substitute for Android runtime or provider tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/webedge'
def source(name):
    return (SRC / (name + '.kt')).read_text()

class RecoveryPolicyTest(unittest.TestCase):
    def test_private_activity_uses_existing_lock(self):
        ns = '{http://schemas.android.com/apk/res/android}'
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        activity = next(a for a in manifest.findall('.//activity') if a.get(ns+'name') == '.RecoveryArchiveActivity')
        self.assertEqual(activity.get(ns+'exported'), 'false')
        ui = source('RecoveryArchiveActivity')
        self.assertIn(': BiometricBrowserActivity()', ui)
        self.assertIn('FLAG_SECURE', ui)
        self.assertIn('setRecentsScreenshotEnabled(false)', ui)
        self.assertIn('deferBrowserAction()', ui)

    def test_fresh_offer_before_store_start(self):
        main = source('MainActivity').split('override fun onCreate',1)[1].split('private fun initializeBrowser',1)[0]
        self.assertLess(main.index('RecoveryArchiveActivity.shouldOffer'), main.index('startProtectedBrowser'))
        self.assertIn('!BrowserLockPolicy.enabled(this)',main)
        self.assertIn('Store.unusedForRecovery() && ProtectedRecords.canRestoreArchive(context)', source('RecoveryArchiveActivity'))

    def test_store_guard_and_google_profile(self):
        store = source('Store').split('internal fun restoreBeforeStore',1)[1].split('private const val TAG',1)[0]
        self.assertIn('check(instance == null)',store)
        self.assertLess(store.index('check(instance == null)'),store.index('ProtectedRecords.restoreArchive'))
        self.assertIn('getSharedPreferences("google_account"',store)

    def test_no_overwrite_and_preflight(self):
        records = source('ProtectedRecords')
        body = records.split('internal fun restoreArchive',1)[1].split('private fun statusLocked',1)[0]
        self.assertIn('check(emptyForRecovery(sp))',body)
        self.assertLess(body.index('RecordSchema.validate'),body.index('AdaptiveSecurity.wrapRecordsKey'))
        self.assertLess(body.index('RecordWritePolicy.sealVerified'),body.index('editor.commit()'))
        self.assertIn('markFailure(e)',body)
        self.assertNotIn('editor.remove',body)
        self.assertIn('names.none { sp.contains(it) }',records)

    def test_snapshot_and_cipher_verification(self):
        records = source('ProtectedRecords').split('internal fun snapshotForRecovery',1)[1].split('private fun emptyForRecovery',1)[0]
        self.assertIn('synchronized(lock)',records)
        self.assertIn('check(!failed && syncHealthy())',records)
        self.assertIn('key(sp, verifyWrapper = true)',records)
        ui = source('RecoveryArchiveActivity')
        self.assertLess(ui.index('RecoveryArchive.open(archive, key)'),ui.index('Store.restoreBeforeStore'))
        self.assertIn('actual.contentEquals(archive) && RecoveryArchive.open(actual, key) == entries',ui)
        self.assertIn('RecoverySnapshotValidation.validate(entries)',ui)

    def test_no_key_persistence_and_stale_picker(self):
        ui = source('RecoveryArchiveActivity')
        self.assertIn('if (!pickerOpen) return',ui)
        self.assertIn('DocumentsContract.isDocumentUri(this, uri)',ui)
        self.assertIn('ContentResolver.SCHEME_CONTENT',ui)
        self.assertIn('isSaveEnabled = false',ui)
        self.assertIn('EXTRA_IS_SENSITIVE',ui)
        self.assertNotIn('putExtra("key"',ui)
        self.assertNotIn('onSaveInstanceState',ui)
        self.assertIn('recoveryKey = null',ui)

    def test_opt_in_and_private_exclusion(self):
        self.assertNotIn('ProtectedRecords.enable(',source('MainActivity'))
        self.assertIn('running || isPrivateMode()',source('BrowserToolsController'))
        self.assertIn('normalTabs.map',source('TabManager'))
        self.assertIn('const val PREFIX = "s2:"',source('RecordCipher'))

if __name__ == '__main__': unittest.main(verbosity=2)
