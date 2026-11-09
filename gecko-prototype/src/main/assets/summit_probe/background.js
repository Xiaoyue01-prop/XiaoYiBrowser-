// Diagnostic-only background handshake.
// This uses the extension-level delegate, independently of content-script messaging.
(() => {
  try {
    Promise.resolve(browser.runtime.sendNativeMessage('browser', {
      type: 'background-start',
      timestamp: Date.now()
    })).catch(error => {
      console.error('[Summit Probe] background native message failed:', error);
    });
  } catch (error) {
    console.error('[Summit Probe] background native message threw:', error);
  }
})();
