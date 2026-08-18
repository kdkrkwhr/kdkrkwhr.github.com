# Dong-ki Kim (Jayden) — Tech Blog

> Personal technical blog built with **Jekyll** and published via **GitHub Pages**.

## 🌐 Live site

🔗 https://kdkrkwhr.github.io

## 🧰 Stack

- **Jekyll** (static site generator, built into GitHub Pages)
- **Kramdown** markdown processor (GitHub Pages default)
- Custom `architect` theme layout (`_layouts`, `_includes`, `stylesheets`)

## 📝 Writing posts

1. Add yourself to `_data/authors.yml` (GitHub username enables the profile avatar + link):

```yaml
kdkrkwhr:
    name: Kim, DongKi
    username: kdkrkwhr
    email: kdkdongki1997@gmail.com
```

2. Create `YYYY-MM-DD-title.md` under `_posts/` with front matter:

```yaml
---
layout: post
date: 2026-08-18
title: Your Post Title
author: kdkrkwhr
tags: java spring ai
excerpt: Short summary shown in the post list.
---
```

3. Push to the `master` branch → GitHub Pages publishes automatically.

## 💻 Local preview

```bash
gem install jekyll bundler
bundle install
bundle exec jekyll serve
```

Generated static files land in `_site/`.

## 📌 Notes

- GitHub Pages supports **kramdown** only.
- Set `published: false` in front matter to exclude a draft from the build.
