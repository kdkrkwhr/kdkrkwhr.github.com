(function () {
  function siteTheme() {
    return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'default';
  }

  function convertBlocks() {
    document.querySelectorAll('code.language-mermaid').forEach(function (code) {
      var block = code.closest('.highlighter-rouge') || code.closest('pre');
      if (!block) return;

      var div = document.createElement('div');
      div.className = 'mermaid';
      div.textContent = code.textContent.trim();
      block.replaceWith(div);
    });
  }

  function render() {
    if (typeof mermaid === 'undefined') return;

    convertBlocks();
    var nodes = document.querySelectorAll('.mermaid');
    if (!nodes.length) return;

    mermaid.initialize({
      startOnLoad: false,
      theme: siteTheme(),
      securityLevel: 'strict'
    });

    mermaid.run({ nodes: nodes }).catch(function (err) {
      console.error('Mermaid render failed:', err);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', render);
  } else {
    render();
  }
})();
