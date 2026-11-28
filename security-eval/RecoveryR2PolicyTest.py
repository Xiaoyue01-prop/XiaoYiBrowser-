from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/webedge'
def src(name): return (SRC / (name + '.kt')).read_text()
class R2Policy(unittest.TestCase):
    def test_encrypted_only_write(self):
        s = src('ThumbnailStorage'); store = src('Store')
        self.assertIn('stream.write(encrypted)', s)
        self.assertLess(s.index('ThumbnailCipher.seal'), s.index('target.startWrite'))
        self.assertIn('target.failWrite(stream)', s)
        self.assertIn('thumbnailStorage.save(tab.id, jpeg)', store)
        self.assertIn('ByteArrayOutputStream()', store)
        self.assertNotIn('BitmapFactory.decodeFile', store)
    def test_missing_key_guard(self):
        s = src('ThumbnailStorage')
        self.assertIn('if (store.containsAlias(ALIAS))', s)
        self.assertLess(s.index('check(ids().all'), s.index('KeyGenerator.getInstance'))
        self.assertIn('ThumbnailCipher.open(key(false), id, old)', s)
        self.assertIn('check(read(target)?.contentEquals(encrypted) == true)', s)
    def test_migration_background(self):
        self.assertIn('io.execute { store.migrateLegacyThumbnails() }', src('TabPersistenceController'))
        self.assertIn('synchronized(this) { thumbnailStorage.migrateLegacy(id) }', src('Store'))
        self.assertIn('if (ThumbnailCipher.isLegacy(raw)) save(id, raw)', src('ThumbnailStorage'))
    def test_backup_exclusion(self):
        root = ET.parse(ROOT / 'app/src/main/res/xml/summit_data_extraction_rules.xml')
        for tag in ['cloud-backup','device-transfer']:
            self.assertTrue(any(x.get('domain') == 'file' and x.get('path') == 'tab_thumbnails/' for x in root.findall(tag+'/exclude')))
    def test_confirmation_before_picker(self):
        s = src('RecoveryArchiveActivity')
        self.assertLess(s.index('keyInput.text.toString().trim() != generated'), s.index('createDocument.launch'))
        self.assertIn('keyInput.text.clear()',s)
        self.assertIn('IME_FLAG_NO_PERSONALIZED_LEARNING', s)
    def test_timestamp_only_after_readback(self):
        s = src('RecoveryArchiveActivity')
        self.assertLess(s.index('actual.contentEquals(archive) && RecoveryArchive.open(actual, key) == entries'), s.index('RecoveryReminder.recordVerified'))
        reminder = src('RecoveryReminder')
        self.assertIn('putLong(LAST, System.currentTimeMillis()).commit()',reminder)
        self.assertNotIn('putString', reminder)
    def test_reminder_in_menu(self):
        self.assertIn('RecoveryReminder.due(activity)', src('MenuController'))
        self.assertIn('RecoveryReminder.description(activity)', src('MenuController'))
        self.assertNotIn('RecoveryReminder', src('BrowserToolsController'))
    def test_opt_in(self):
        self.assertNotIn('ProtectedRecords.enable(', src('MainActivity'))
if __name__ == '__main__': unittest.main(verbosity=2)
