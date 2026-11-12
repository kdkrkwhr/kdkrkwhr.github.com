# Blog visual refresh implementation plan

> Native implementation with regression checks.

Goal: An original, restrained editorial UI inspired by the reference's whitespace and clarity, not a copy.
Architecture: Preserve Jekyll, GitHub Pages, post URLs, source posts, analytics, comments, diagrams and automatic publishing. Change presentation templates/CSS and small progressive-enhancement scripts only.

1. Replace the image hero with a typographic introduction, latest article and compact project links. Give article discovery the primary space.
2. Add accessible title/summary search and category filters, URL state, empty state and incremental display. Keep all articles accessible without JavaScript.
3. Unify header, sidebar and article typography; keep dark mode, add article outline and mobile navigation with Escape/close handling. Protect wide code/tables and keyboard focus.
4. Verify templates and behavior, inspect desktop/mobile light/dark layouts, preserve all posts/URLs. Commit and push only this scoped change. Verify GitHub Pages build and live UI.

Review focus: empty results and reset; combined category/search; back/forward navigation; blocked localStorage; long Korean titles and wide article tables; no-JavaScript access.
