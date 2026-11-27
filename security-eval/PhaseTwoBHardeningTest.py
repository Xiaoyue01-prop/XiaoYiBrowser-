"""Source integration guards. Runtime Keystore and disk behavior require Android tests."""
from pathlib import Path
import unittest
import re

SRC = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/webedge'

def source(name):
    return (SRC / (name + '.kt')).read_text()

class PhaseTwoBHardeningTest(unittest.TestCase):
    def test_migration_preflight_before_key(self):
        body = source('ProtectedRecords').split('suspend fun enable(', 1)[1]
        self.assertIn('RecordWritePolicy.preflight', body)
        self.assertLess(body.index('RecordWritePolicy.preflight'), body.index('AdaptiveSecurity.wrapRecordsKey'))
        self.assertLess(body.index('RecordWritePolicy.sealVerified'), body.index('editor.commit()'))

    def test_failed_blocks_removal_and_write(self):
        body = source('ProtectedRecords').split('fun write(', 1)[1].split('suspend fun enable', 1)[0]
        self.assertIn('check(!failed)', body)
        self.assertNotIn('!failed ||', body)
        self.assertLess(body.index('validateExisting'), body.index('editor.remove(name)'))

    def test_active_write_rechecks_wrapper(self):
        body = source('ProtectedRecords').split('fun write(', 1)[1].split('suspend fun enable', 1)[0]
        self.assertIn('key(sp, verifyWrapper = true)', body)
        self.assertIn('RecordWritePolicy.sealVerified', body)
        self.assertIn('val enabled = active(sp)', body)

    def test_schema_prevents_loss(self):
        records = source('ProtectedRecords')
        self.assertIn('RecordSchema.validate', records)
        schema = source('RecordSchema')
        self.assertIn('JsonToken.END_DOCUMENT', schema)
        self.assertIn('check(seen.add', schema)
        self.assertIn('SyncJson.decode', schema)

    def test_single_store(self):
        self.assertIn('class Store private constructor', source('Store'))
        for activity in ('MainActivity', 'OverlayBrowserActivity'):
            self.assertIn('Store.get(this)', source(activity))

    def test_disconnect_result(self):
        self.assertIn('fun disableSync(): Boolean', source('Store'))
        menu = source('MenuController')
        self.assertEqual(menu.count('if (!store.disableSync())'), 3)

    def test_store_reads_strictly_even_before_opt_in(self):
        store = source('Store')
        calls = re.findall(r'ProtectedRecords\.readArray\([^\n]+', store)
        self.assertEqual(len(calls), 3)
        self.assertTrue(all('strictSchema = true' in call for call in calls))
        self.assertIn('if (strictSchema || active(sp)) RecordSchema.validate', source('ProtectedRecords'))

    def test_sync_binding_is_checked_before_editor(self):
        body = source('ProtectedRecords').split('fun write(', 1)[1].split('suspend fun enable', 1)[0]
        self.assertIn('validateSyncBinding(sp)', body)
        self.assertLess(body.index('validateSyncBinding(sp)'), body.index('sp.edit()'))
        self.assertIn('sp.contains("sync_account_v1") == sp.contains("sync_state_v1")', source('ProtectedRecords'))

    def test_no_direct_plaintext_record_writes(self):
        body = source('ProtectedRecords').split('fun write(', 1)[1].split('suspend fun enable', 1)[0]
        self.assertIn('val dataKey = if (enabled) key(sp, verifyWrapper = true) else null', body)
        self.assertIn('editor.putString(name, if (dataKey != null) RecordWritePolicy.sealVerified(dataKey, name, value) else value)', body)
        for path in SRC.glob('*.kt'):
            self.assertNotRegex(path.read_text(), r'(?:putString|remove)\(\s*"(?:history|bookmarks|closed_tabs|saved_tabs|tab_groups|sync_state_v1)"', path.name)

    def test_remains_opt_in(self):
        self.assertNotIn('ProtectedRecords.enable(', source('MainActivity'))
        self.assertIn('ProtectedRecords.enable(host)', source('BrowserToolsController'))
        self.assertIn('const val PREFIX = "s2:"', source('RecordCipher'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
