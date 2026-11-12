(function () {
  var content = document.querySelector('.post-content');
  if (!content) return;
  content.querySelectorAll('table').forEach(function (table) {
    if (table.parentElement.classList.contains('table-scroll')) return;
    var wrapper = document.createElement('div');
    wrapper.className = 'table-scroll';
    wrapper.tabIndex = 0;
    wrapper.setAttribute('role', 'region');
    wrapper.setAttribute('aria-label', '가로로 스크롤할 수 있는 표');
    table.parentNode.insertBefore(wrapper, table);
    wrapper.appendChild(table);
  });
  var outline = document.getElementById('article-outline');
  var links = document.getElementById('outline-links');
  if (!outline || !links) return;
  var headings = Array.from(content.querySelectorAll('h2, h3'));
  headings.forEach(function (heading, index) {
    if (!heading.id) {
      var id = 'section-' + index;
      while (document.getElementById(id)) id += '-section';
      heading.id = id;
    }
    var link = document.createElement('a');
    link.href = '#' + encodeURIComponent(heading.id);
    link.textContent = heading.textContent;
    if (heading.tagName === 'H3') link.className = 'outline-subheading';
    links.appendChild(link);
  });
  outline.hidden = headings.length === 0;
})();
