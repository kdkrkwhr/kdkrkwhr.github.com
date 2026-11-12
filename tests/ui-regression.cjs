// Run with NODE_PATH pointing to an installation of jsdom.
const {JSDOM} = require('jsdom');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
function script(name) { return fs.readFileSync(path.join(root, 'assets/js', name), 'utf8'); }
const dom = new JSDOM(`<html data-theme="light"><body>
<header class="site-header"><button id="theme-toggle"></button><button id="nav-toggle"></button><nav id="site-nav"><a href="#posts">글</a></nav></header>
<button class="category-chip" data-category="all"></button><button class="category-chip" data-category="ai"></button><button class="category-chip" data-category="java"></button>
<input id="post-search"><span id="post-count"></span><p id="filter-status"></p><div id="empty-state"></div><button id="filter-reset"></button><button id="load-more"></button>
<ul id="post-grid">${Array.from({length:26},(_,i)=>`<li class="post-card" data-category="${i%2?'ai':'java'}"><a href="/post-${i}"><h3>${i%2?'AI 에이전트':'Spring 트랜잭션'} ${i}</h3></a></li>`).join('')}</ul>
<div class="post-content"><h2>개요</h2><h3 id="existing">이미 있는 앵커</h3><table><tr><td>긴 표</td></tr></table></div><aside id="article-outline" hidden><nav id="outline-links"></nav></aside>
</body></html>`,{url:'https://example.org/',runScripts:'outside-only'});
const w=dom.window,d=w.document;
const visible=()=>d.querySelectorAll('.post-card:not([hidden])').length;
w.eval(script('archive.js'));
assert.equal(visible(),12); assert.equal(d.querySelector('#post-count').textContent,'26');
d.querySelector('#load-more').click();assert.equal(visible(),24);assert.equal(d.activeElement.getAttribute('href'),'/post-12');
w.dispatchEvent(new w.PopStateEvent('popstate'));assert.equal(visible(),24);
d.querySelector('#load-more').click();assert.equal(visible(),26);assert.equal(d.querySelector('#load-more').hidden,true);
d.querySelector('[data-category="ai"]').click();assert.equal(visible(),12);assert.equal(d.querySelector('#post-count').textContent,'13');
const input=d.querySelector('#post-search');input.value='에이전트 25';input.dispatchEvent(new w.Event('input'));assert.equal(visible(),1);assert.equal(new URL(w.location).searchParams.get('q'),'에이전트 25');
input.value='no result';input.dispatchEvent(new w.Event('input'));assert.equal(visible(),0);assert.equal(d.querySelector('#empty-state').hidden,false);
d.querySelector('#filter-reset').click();assert.equal(visible(),12);assert.equal(d.activeElement,input);assert.equal(w.location.search,'');
w.history.replaceState(null,'','?category=java&q=Spring');w.dispatchEvent(new w.PopStateEvent('popstate'));assert.equal(d.querySelector('[data-category="java"]').getAttribute('aria-pressed'),'true');assert.equal(input.value,'Spring');
Object.defineProperty(w,'localStorage',{get(){throw Error('blocked');}});w.eval(script('theme.js'));d.querySelector('#theme-toggle').click();assert.equal(d.documentElement.dataset.theme,'dark');d.querySelector('#theme-toggle').click();assert.equal(d.documentElement.dataset.theme,'light');
d.querySelector('#nav-toggle').click();assert.equal(d.querySelector('#nav-toggle').getAttribute('aria-expanded'),'true');d.dispatchEvent(new w.KeyboardEvent('keydown',{key:'Escape'}));assert.equal(d.querySelector('#nav-toggle').getAttribute('aria-expanded'),'false');
d.querySelector('#nav-toggle').click();d.querySelector('#site-nav a').click();assert.equal(d.querySelector('#nav-toggle').getAttribute('aria-expanded'),'false');
w.eval(script('article.js'));assert.equal(d.querySelectorAll('#outline-links a').length,2);assert.equal(d.querySelectorAll('.table-scroll').length,1);assert.equal(d.querySelector('#existing').id,'existing');assert.equal(d.querySelector('#article-outline').hidden,false);
console.log('PASS: pagination, combined filters, empty/reset, URL restoration, blocked storage, theme, menu Escape/navigation, outline, table wrapper');
