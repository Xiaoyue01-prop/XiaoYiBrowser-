"""Recovery UI relocation: signed-in and signed-out routes, no duplicate dashboard entry."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/webedge'

def source(name):
    return (SRC / (name + '.kt')).read_text()

class RecoveryMenuPlacementTest(unittest.TestCase):
    def test_entry_moved(self):
        self.assertNotIn('R.string.recovery_export_button', source('BrowserToolsController'))
        self.assertEqual(source('MenuController').count('R.string.recovery_export_button'), 1)

    def test_both_account_states(self):
        menu = source('MenuController')
        account = menu.split('private fun showGoogleAccount(',1)[1].split('private fun showGoogleSyncConfirmation(',1)[0]
        signed_out, signed_in = account.split('panel.body.addView(buildGoogleAccountRow(account))',1)
        self.assertIn('if (account == null)', signed_out)
        self.assertIn('addRecoveryEntry(panel)', signed_out)
        self.assertIn('addRecoveryEntry(panel)', signed_in)
        self.assertIn('googleAccount.signIn', signed_out)
        self.assertIn('showGoogleAccount(account)', menu.split('private fun buildGoogleAccountRow',1)[0])

    def test_guards_before_launch(self):
        entry = source('MenuController').split('private fun addRecoveryEntry',1)[1].split('private fun showGoogleAccount',1)[0]
        launch = entry.index('activity.startActivity')
        for guard in ['tabManager.isPrivateMode', '!BrowserLockPolicy.allowBrowserWindow(activity)', 'googleSync.busy', 'store.flush()']:
            self.assertLess(entry.index(guard), launch)
        self.assertNotIn('EXTRA_RESTORE', entry)

if __name__ == '__main__': unittest.main(verbosity=2)
