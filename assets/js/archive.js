(function () {
  var grid = document.getElementById('post-grid');
  if (!grid) return;
  var cards = Array.from(grid.querySelectorAll('.post-card'));
  var chips = Array.from(document.querySelectorAll('.category-chip'));
  var search = document.getElementById('post-search');
  var count = document.getElementById('post-count');
  var status = document.getElementById('filter-status');
  var empty = document.getElementById('empty-state');
  var more = document.getElementById('load-more');
  var limit = 12;
  var category = 'all';
  function restore() {
    var params = new URLSearchParams(location.search);
    var requested = params.get('category') || 'all';
    category = chips.some(function (chip) { return chip.dataset.category === requested; }) ? requested : 'all';
    search.value = params.get('q') || '';
    var savedLimit = history.state && history.state.archiveLimit;
    limit = Number.isSafeInteger(savedLimit) && savedLimit >= 12 ? savedLimit : 12;
    render();
  }
  function save() {
    var url = new URL(location.href);
    if (category === 'all') url.searchParams.delete('category'); else url.searchParams.set('category', category);
    if (search.value.trim()) url.searchParams.set('q', search.value.trim()); else url.searchParams.delete('q');
    history.replaceState(Object.assign({}, history.state, { archiveLimit: limit }), '', url);
  }
  function render() {
    var query = search.value.trim().toLocaleLowerCase();
    var matching = cards.filter(function (card) {
      return (category === 'all' || card.dataset.category === category) && card.textContent.toLocaleLowerCase().includes(query);
    });
    cards.forEach(function (card) { card.hidden = true; });
    matching.slice(0, limit).forEach(function (card) { card.hidden = false; });
    chips.forEach(function (chip) {
      var active = chip.dataset.category === category;
      chip.classList.toggle('is-active', active);
      chip.setAttribute('aria-pressed', String(active));
    });
    count.textContent = matching.length;
    status.textContent = matching.length + '개의 글 중 ' + Math.min(limit, matching.length) + '개 표시';
    empty.hidden = matching.length > 0;
    more.hidden = matching.length <= limit;
  }
  chips.forEach(function (chip) { chip.addEventListener('click', function () { category = chip.dataset.category; limit = 12; save(); render(); }); });
  search.addEventListener('input', function () { limit = 12; save(); render(); });
  more.addEventListener('click', function () {
    var previouslyVisible = cards.filter(function (card) { return !card.hidden; });
    limit += 12; save(); render();
    var firstNew = cards.find(function (card) { return !card.hidden && !previouslyVisible.includes(card); });
    if (firstNew) firstNew.querySelector('a').focus();
  });
  document.getElementById('filter-reset').addEventListener('click', function () { category = 'all'; search.value = ''; limit = 12; save(); render(); search.focus(); });
  window.addEventListener('popstate', restore);
  restore();
})();
