(function(){
  if (window !== window.top || !/^https?:$/.test(location.protocol) || window.__summitDesktopViewport) return;
  window.__summitDesktopViewport = true;
  var observer = new MutationObserver(update);
  var observedHead = null;

  function update(){
    var head = document.head;
    if (!head) return;
    if (observedHead !== head) {
      observer.disconnect();
      observer.observe(head, {childList: true, subtree: true, attributes: true, attributeFilter: ['name', 'content']});
      observedHead = head;
    }
    var metas = head.querySelectorAll('meta[name="viewport" i]');
    if (!metas.length) {
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      meta.content = 'width=980';
      head.appendChild(meta);
      return;
    }
    metas.forEach(function(meta){
      var content = meta.content || '';
      var width = 980;
      var tokens = content.split(/[,;]/).map(function(token){ return token.trim(); }).filter(function(token){
        var key = token.split('=')[0].trim().toLowerCase();
        if (key === 'width') {
          var authored = Number(token.split('=')[1]);
          if (Number.isFinite(authored)) width = Math.max(width, authored);
        }
        return token && !/^(width|initial-scale|minimum-scale|maximum-scale|user-scalable)$/.test(key);
      });
      var next = ['width=' + width].concat(tokens).join(', ');
      if (next !== content) meta.content = next;
    });
  }

  observer.observe(document, {childList: true, subtree: true});
  update();
})();
