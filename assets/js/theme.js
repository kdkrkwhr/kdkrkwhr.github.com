(function () {
  var root = document.documentElement;
  var toggle = document.getElementById('theme-toggle');
  var navToggle = document.getElementById('nav-toggle');
  var sidebar = document.getElementById('sidebar');

  function setTheme(theme) {
    root.dataset.theme = theme;
    localStorage.setItem('theme', theme);
  }

  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      setTheme(next);
    });
  }

  if (navToggle && sidebar) {
    navToggle.addEventListener('click', function () {
      var open = sidebar.classList.toggle('is-open');
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }
})();
