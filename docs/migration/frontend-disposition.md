# Frontend Inventory & Disposition Recommendation

Scope: the entire user-facing frontend of Fat Free CRM — the server-rendered HAML view
layer, the server-rendered JavaScript (SJR) responses that power AJAX interactions, and
the Sprockets asset pipeline (CoffeeScript / SCSS / Bootstrap / jQuery). This document
inventories what exists today and lays out the disposition options for the Spring
migration. **The final choice between the options in §5 is a client decision — it is
deliberately not made here.**

---

## 1. View Layer Inventory (`app/views/`)

All HTML is rendered server-side with **Haml** templates inside Rails layouts. Totals
(current `master`):

| Kind | Count | Notes |
|---|---|---|
| `*.html.haml` templates & partials | 231 | Full pages + partials |
| `*.js.haml` / `*.js.erb` (server-rendered JS) | 107 | AJAX responses — see §3 |
| `*.builder` (Atom/RSS/XLS feeds) | 14 | e.g. `home/index.atom.builder`, `layouts/header.xls.builder` |
| Other (`shared/_paginate.haml`) | 1 | |
| **Total HAML** | **338** (~6,000 LOC) | |

### Per-directory breakdown

| Directory | HTML HAML | JS templates | Purpose |
|---|---:|---:|---|
| `accounts/` | 13 | 7 | Account CRUD, index, sidebar |
| `admin/` | 58 | 35 | Users, groups, custom fields, field groups, tags, research tools, settings, plugins |
| `campaigns/` | 14 | 7 | Campaign CRUD + metrics sidebar |
| `contacts/` | 17 | 7 | Contact CRUD |
| `leads/` | 18 | 10 | Lead CRUD **+ convert/promote/reject workflow** |
| `opportunities/` | 12 | 8 | Opportunity CRUD + contacts association |
| `tasks/` | 17 | 10 | Task buckets, complete/uncomplete, filter |
| `comments/` | 4 | 4 | Inline comments on any entity |
| `emails/` | 1 | 1 | Dropbox-archived email display |
| `entities/` | 6 | 7 | Shared entity behaviors: attach, discard, related lists, versions, permissions, custom-field sections, advanced (Ransack) search |
| `fields/` | 6 | 1 | Custom-field group rendering (dynamic per-entity forms) |
| `home/` | 10 | 2 | Activity dashboard + Atom/RSS/XLS feeds |
| `lists/` | 2 | 2 | Saved searches ("Lists") |
| `users/` | 9 | 6 | Profile, avatar upload, password change |
| `versions/` | 3 | 0 | PaperTrail audit-trail timeline |
| `devise/` | 8 | 0 | Login, registration, password reset (Devise-provided flows) |
| `layouts/` | 11 | 0 | `application.html.haml`, header, sidebar, jumpbox, footer, admin layout |
| `shared/` | 20 | 0 | Cross-entity partials: comments, address, tags, tasks, timeline, paginate, export, select popup, recent items |
| `dropbox_mailer/`, `user_mailer/`, `subscription_mailer/` | 2 | 0 | Email bodies (HAML, run through `premailer`) |

Structural notes:

- **Heavy partial reuse.** Entity pages are assembled from `shared/` and `entities/`
  partials (title bar, sidebar, comments, tasks, timeline, tags, custom-field
  sections). Any re-implementation reproduces components, not 338 independent pages.
- **Custom fields render dynamically.** `fields/_group*.haml` +
  `entities/_section_custom_fields.html.haml` build form inputs at runtime from the
  `Field`/`FieldGroup` DB tables (the dynamic-schema feature). The new frontend must
  render forms from field metadata, not static markup.
- **Hook system injects UI.** `lib/fat_free_crm/callback.rb` lets plugins insert view
  fragments at named hook points inside templates. Plugin-based UI extension has no
  direct equivalent in either target option and needs an explicit decision if plugins
  are in scope.
- **Non-HTML index formats** (Atom, RSS, CSV, XLS via `respond_to :atom, :csv, :rss, :xls`)
  are part of the view layer and must be re-homed (likely as API export endpoints).

## 2. Asset Pipeline Inventory

Assets are compiled by **Sprockets** (`sprockets-rails`), with directives in
`app/assets/javascripts/application.js.erb` and `app/assets/stylesheets/application.css.erb`.
The ERB manifests iterate `Gem.loaded_specs` to auto-include assets from `ffcrm_*`
plugin gems — the plugin/hook system reaches into the asset pipeline too.

### JavaScript (CoffeeScript, ~1,140 LOC in 16 files)

| File | Responsibility |
|---|---|
| `crm.js.coffee` | Core namespace: panel expand/collapse, form show/hide, flip forms, per-page settings |
| `crm_classes.js.coffee` | Menu / popup / date-picker widget classes |
| `crm_comments.js.coffee` | Inline comment editing |
| `crm_select2.js.coffee` | Select2 dropdown initialization (incl. AJAX autocomplete) |
| `crm_sortable.js.coffee` | jQuery UI drag-and-drop sorting (field groups, task order) |
| `crm_tags.js.coffee` | Tag editing widgets |
| `crm_textarea_autocomplete.js.coffee` | @-mention style autocomplete in comments |
| `crm_validations.js.coffee` | Client-side validation hooks |
| `crm_loginout.js.coffee` | Login/logout form behavior |
| `search.js.coffee`, `lists.js.coffee` | Ransack advanced search UI + saved lists |
| `datepicker.js.coffee` | jQuery UI datepicker/timepicker wiring |
| `format_buttons.js.coffee`, `pagination.js.coffee`, `timeago.js.coffee` | Index-format toggles, per-page selector, relative timestamps |
| `admin/fields.js.coffee` | Admin custom-field editor (sortable groups, subforms) |

### Stylesheets (SCSS via `sassc-rails`, 16+ files)

`base.scss`, `common.scss`, `header.scss`, `bootstrap-custom.scss` (Bootstrap 5.2
variable overrides), plus per-feature sheets (`advanced_search`, `fields`, `lists`,
`format_buttons`, `index_headers`, `groups`, `print`, `admin/fields`, …).

### Frontend-relevant gems

| Gem | Role | Migration note |
|---|---|---|
| `bootstrap ~> 5.2` (Gemfile) | CSS framework | Portable: usable from an SPA or Thymeleaf |
| `sassc-rails`, `coffee-rails`, `uglifier` (Gemfile) | SCSS / CoffeeScript compilation, JS minification | Rails-only; replaced by any modern bundler. `coffee-script-source` is pegged to `~> 1.8` (2014) |
| `jquery-rails`, `jquery-migrate-rails`, `jquery-ui-rails` | jQuery + jQuery UI (sortable, datepicker, effects) | Legacy stack; jQuery UI has no Spring equivalent — behaviors must be rebuilt |
| `select2-rails` | Rich dropdowns/autocomplete | Select2 works standalone, but current wiring is Rails-specific |
| `rails3-jquery-autocomplete`, `responds_to_parent2` | Autocomplete endpoints; iframe-based file-upload responses (avatar upload) | Rails-only |
| `ransack_ui` | Advanced-search query builder UI, coupled to Ransack grammar | Whole search UI needs redesign against the new API's query language |
| `font-awesome-rails` | Icons | Portable |
| `simple_form`, `dynamic_form`, `country_select`, `will_paginate` | Form builders / pagination helpers used throughout HAML | Rails-only view helpers |
| `premailer` | Inlines CSS into HTML emails | Replace with a Java equivalent for mailer templates |

## 3. Server-Rendered JS (SJR) — the critical coupling

`ApplicationController` declares (`app/controllers/application_controller.rb:24-27`):

```ruby
respond_to :html, only: %i[index show auto_complete]
respond_to :js
respond_to :json, :xml, except: :edit
respond_to :atom, :csv, :rss, :xls, only: :index
```

`respond_to :js` applies to **every controller**. Nearly all in-page interactivity works
via Rails UJS (`remote: true` links/forms, 38 usages in views): the browser submits an
AJAX request, and the server responds with a **JavaScript program** (rendered from a
`.js.haml` template) that the browser `eval`s. Those scripts render HAML partials
server-side and splice the resulting HTML into the DOM:

```
┌─────────── current SJR flow (does NOT translate to a JSON API) ───────────┐
│                                                                           │
│  browser ──(XHR, remote: true)──▶ Rails controller                        │
│                                        │ renders create.js.haml           │
│                                        ▼                                  │
│  browser ◀──(text/javascript)── "$('#leads').prepend('<li>…HTML…</li>');  │
│     │                            $('#dom_id').effect('highlight');"       │
│     └── eval()s script → DOM updated with server-rendered HTML            │
└───────────────────────────────────────────────────────────────────────────┘
```

**107 such templates** exist. Behaviors that depend on them:

| Behavior | Templates |
|---|---|
| Inline create/edit/delete of every entity without page reload (form slides open in the index page, new row prepended, sidebar refreshed) | `{accounts,campaigns,contacts,leads,opportunities}/{new,create,edit,update,destroy,index,show}.js.haml` |
| **Lead conversion workflow** (convert → promote into Contact/Account/Opportunity, reject) | `leads/{convert,promote,reject}.js.haml` |
| Task buckets: complete/uncomplete with fade effects, bucket filtering, sidebar counts | `tasks/{complete,uncomplete,filter,create,destroy,…}.js.haml` |
| Inline comments on any entity | `comments/{create,edit,update,destroy}.js.haml` |
| Shared entity ops: attach/discard related records, related-record tabs (contacts/leads/opportunities), PaperTrail version timeline, subscription toggle | `entities/*.js.haml` |
| Dashboard filtering (assets/users/duration menus) | `home/{index,options}.js.haml` |
| Saved lists create/delete | `lists/{create,destroy}.js.haml` |
| Admin: users (suspend/reactivate/confirm), groups, tags, custom fields & field groups (incl. drag-to-reorder persisted via AJAX) | 35 templates under `admin/` |
| Profile: avatar upload (via `responds_to_parent2` iframe trick), password change | `users/*.js.haml` |
| Dynamic custom-field group swap when changing e.g. contact type | `fields/group.js.erb` |
| Ransack advanced-search panel updates | `entities/_search` + `ransack_ui` assets |

Additional server-side JS is generated by helpers in `app/helpers/application_helper.rb`
(e.g. `refresh_sidebar`, `hide`/`show`, `link_to_inline`), so the coupling is not
limited to the template files.

**Implication:** a JSON API returns data, not executable JS + HTML fragments. Every one
of these interactions must be **re-implemented client-side** (option A) or re-rendered
as HTML fragments/full pages (option B). This — not the static page templates — is the
bulk of the frontend migration effort.

## 4. What does *not* carry over automatically

- Haml templates (338) — no Haml engine in Spring; markup must be ported.
- All 107 SJR templates and the UJS `remote: true` pattern.
- Rails view helpers used pervasively: `simple_form`, `will_paginate`, `dynamic_form`,
  `link_to_remote`-style helpers, `dom_id`, I18n `t()` lookups (locale files under
  `config/locales/` — 18 locale files — need a strategy in either option).
- Sprockets pipeline incl. ERB manifests and plugin asset auto-inclusion.
- CoffeeScript sources (would be transpiled/rewritten to modern JS/TS in either option).
- Devise-rendered auth pages (login, password reset, registration) — tied to whatever
  auth mechanism replaces Devise in Spring.

## 5. Disposition options (client decision)

```
 Option A: SPA + JSON API                    Option B: Spring + Thymeleaf (SSR)
 ┌──────────────┐   JSON    ┌────────────┐   ┌───────────────────────────────┐
 │ SPA (React/  │◀────────▶│ Spring Boot │   │ Spring Boot MVC               │
 │ Vue/Angular) │           │ JSON API   │   │  Controllers → Thymeleaf HTML │
 │ owns all UI  │           │ (no views) │   │  + htmx/Turbo-style AJAX for  │
 │ state & DOM  │           └────────────┘   │    fragment swaps             │
 └──────────────┘                            └───────────────────────────────┘
  SJR templates → client components           SJR templates → fragment endpoints
  API is UI-agnostic, reusable                UI still coupled to server
```

### Option A — Separate SPA consuming the new JSON API

Rebuild the UI as a single-page application (React/Vue/Angular); Spring exposes only the
JSON API.

**Pros**
- Clean separation: the JSON API stays pure and is reusable for mobile/integrations;
  no server-side HTML rendering in Spring at all.
- The 107 SJR behaviors map naturally to client-side component state — arguably a
  *simpler* model than reproducing splice-HTML-fragments semantics.
- Dynamic custom-field forms fit well: fetch field metadata as JSON, render forms
  client-side.
- Retires the entire legacy stack (jQuery, jQuery UI, CoffeeScript, Sprockets) in one
  move; modern tooling and hiring pool.

**Cons**
- Largest frontend effort: every page and interaction rebuilt from scratch
  (~231 HTML templates + 107 interactions + admin area).
- Requires API completeness up front — the SPA can't ship until endpoints for
  everything (incl. Ransack-equivalent advanced search, exports, avatar upload) exist.
- New concerns Rails handled implicitly: client-side routing, auth token handling,
  CSRF, i18n (20+ locales), error handling, SEO (minor for an internal CRM).
- Two deployables to build, version, and operate.

### Option B — Server-side templating in Spring (Thymeleaf)

Port HAML → Thymeleaf; Spring MVC controllers return HTML. AJAX interactions return
HTML *fragments* (Thymeleaf fragment rendering) swapped into the DOM by a light
library (htmx or similar) — the closest analogue to the current SJR pattern.

**Pros**
- Architecturally closest to today: templates and partials map ~1:1 to Thymeleaf
  templates and fragments; incremental page-by-page porting is feasible.
- Single deployable; no separate frontend build/team/toolchain.
- Session-based auth, CSRF, and i18n (Spring `MessageSource`) work out of the box,
  mirroring current behavior (18 locale files today).
- Lower JS skill requirement; htmx attribute-driven AJAX replaces UJS `remote: true`
  quite directly.

**Cons**
- SJR templates return *JavaScript*, not HTML — they still can't be ported verbatim;
  each of the 107 interactions must be redesigned as fragment endpoints + swap
  targets (moderate effort, but not free).
- HTML rendering endpoints live alongside the JSON API in the same Spring app,
  diluting the "clean JSON API" goal; UI remains coupled to the backend.
- jQuery-UI behaviors (sortable drag-and-drop, effects, datepicker) still need
  client-side JS regardless.
- Keeps the CRM on a server-rendered paradigm; a later SPA move would mean paying the
  migration cost twice.

### Decision factors for the client

| Factor | Favors A (SPA) | Favors B (Thymeleaf) |
|---|---|---|
| JSON API is a first-class product goal (mobile, integrations) | ✔ | |
| Team has modern JS/TS capability | ✔ | |
| Minimize frontend rewrite risk/effort; Java-centric team | | ✔ |
| Incremental cutover page-by-page | | ✔ |
| Long-term UI modernization | ✔ | |
| Single deployable / simpler ops | | ✔ |

**Recommendation:** present both to the client. If the migration's primary driver is
the JSON API (as the backend workstream suggests), Option A avoids building a
throwaway server-rendered layer; if the driver is platform replacement with minimum
UI risk, Option B is the pragmatic path. A hybrid (B now for parity, A later for
selected modules) is possible but pays the SJR redesign cost in both phases. **This
choice should be made explicitly by the client before frontend work begins.**
