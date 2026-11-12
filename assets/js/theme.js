(function () {
  var root = document.documentElement;
  var toggle = document.getElementById('theme-toggle');
  var navToggle = document.getElementById('nav-toggle');
  var nav = document.getElementById('site-nav');
  function labelTheme() { if (toggle) toggle.setAttribute('aria-label', root.dataset.theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'); }
  if (toggle) toggle.addEventListener('click', function () {
    var next = root.dataset.theme === 'dark' ? 'light' : 'dark';
    root.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (e) {}
    labelTheme();
  });
  labelTheme();
  function setMenu(open) {
    nav.classList.toggle('is-open', open);
    navToggle.setAttribute('aria-expanded', String(open));
    navToggle.setAttribute('aria-label', open ? '메뉴 닫기' : '메뉴 열기');
  }
  if (navToggle && nav) {
    navToggle.addEventListener('click', function () { setMenu(!nav.classList.contains('is-open')); });
    nav.addEventListener('click', function (event) { if (event.target.closest('a')) setMenu(false); });
    document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && nav.classList.contains('is-open')) { setMenu(false); navToggle.focus(); } });
    document.addEventListener('click', function (event) { if (!event.target.closest('.site-header')) setMenu(false); });
  }
})();
