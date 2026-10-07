# API response-format inventory (Phase 0 consumer audit)

Input to [`decisions/0001-api-response-format-scope.md`](decisions/0001-api-response-format-scope.md).
This inventory is based on the current code at the AB-262 epic base, not on assumptions about
production usage. Server logs remain the source of truth for external consumers.

## Where formats are declared

`app/controllers/application_controller.rb` declares the inherited response surface:

```ruby
respond_to :html, only: %i[index show auto_complete]
respond_to :js
respond_to :json, :xml, except: :edit
respond_to :atom, :csv, :rss, :xls, only: :index
```

Controller-level `respond_with` calls can negotiate inherited formats, while direct `format.*`,
renderers, and templates show where concrete behavior is implemented. These declarations are not
proof that each format has a template, route, or production consumer. The grep audit also catches
ordinary Ruby `respond_to?` calls and therefore must be read as a reproducible source inventory,
not a count of endpoints.

## Format-by-format inventory

| Format | Mime | Current implementation | Current actions / notes | Decision |
|---|---|---|---|---|
| `html` | `text/html` | Rails views | Main UI; no template-count claim is made here | Out of scope for `/api/v1`; Rails keeps serving the UI |
| `js` | `text/javascript` | Server-generated JavaScript and Haml `.js` templates | UI interactions; broad inherited declaration | Out of scope for `/api/v1`; Rails keeps serving the UI |
| `json` | `application/json` | `respond_with` / ActiveModel serialization and explicit JSON response blocks | The API's first-class format; JSON error bodies are also present | Keep; only first-class API format |
| `xml` | `application/xml` | `respond_with`, explicit error responses, and the tasks index | Inherited for most non-edit actions; usage must be confirmed in logs | Deprecate, pending log confirmation |
| `atom` | `application/atom+xml` | Builder templates in `app/views/application`, `app/views/home`, and the campaign response | Campaign feed code and feed templates; log usage remains unknown | Deprecate, pending log confirmation |
| `rss` | `application/rss+xml` | Builder templates in `app/views/application`, `app/views/home`, and the campaign response | Campaign feed code and feed templates; log usage remains unknown | Deprecate, pending log confirmation |
| `csv` | `text/csv` | `render csv:` custom renderer in `lib/fat_free_crm/renderers.rb`, using `FatFreeCRM::ExportCSV` | Entity index exports; campaign show also exports its leads; reflected columns include `cf_*` | Keep |
| `xls` | `application/vnd.msexcel` | Registered in `config/initializers/mime_types.rb`; `.xls.builder` templates | SpreadsheetML 2003 XML, not a binary XLS file; entity and home exports | Keep |
| `vcf` | `text/x-vcard` | `helpers.vcard_for` and `send_data` | Contact and lead `show` actions | Keep |

CSV and XLS use different projections. CSV reflects over columns (excluding password/token
columns) and includes custom columns; XLS templates define translated columns per entity.
Production traffic is required before deprecating these user-facing export formats.

## Reproducing the current-code audit

The following exact searches were used:

```bash
grep -rn "respond_to\\|respond_with\\|format\\.\\|render csv\\|send_data" app/controllers
grep -n "format\\|feed\\|\\.atom\\|\\.rss\\|export" config/routes.rb
ls app/views/**/*.{atom,rss,xls}.builder
```

The controller search produced 224 matching source lines. The routes search produced no matches
for those format/feed/export terms. The view listing showed the current Atom/RSS/XLS builder
templates; MIME registration and CSV behavior were checked in
`config/initializers/mime_types.rb`, `lib/fat_free_crm/renderers.rb`, and
`lib/fat_free_crm/export_csv.rb`.

The appendix lists every matching controller file-and-line hit, grouped by inferred action. Line
numbers are one-based. The `formats` notes show explicit local `format.*` forms; other actions
inherit the declarations above where applicable. Helper names and `respond_to?` matches are listed
because the exact command is intentionally a source grep rather than a parser.

### Controller grep hits

| Controller | Action (all matching line numbers; explicit local formats in brackets) |
|---|---|
| `app/controllers/admin/field_groups_controller.rb` | `new(17)`; `edit(25)`; `create(34)`; `update(44)`; `destroy(54)` |
| `app/controllers/admin/fields_controller.rb` | `show(22)`; `new(30)`; `edit(37)`; `create(55)`; `update(69)`; `destroy(79)`; `subform(108,109)` [html] |
| `app/controllers/admin/groups_controller.rb` | `show(22)`; `new(28)`; `edit(34)`; `create(43)`; `update(51)`; `destroy(59)` |
| `app/controllers/admin/plugins_controller.rb` | `index(17)` |
| `app/controllers/admin/research_tools_controller.rb` | `index(10)`; `new(14)`; `edit(18)`; `create(23)`; `update(28)`; `destroy(33)` |
| `app/controllers/admin/tags_controller.rb` | `index(18)`; `new(25)`; `create(40)`; `update(49)`; `destroy(58)` |
| `app/controllers/admin/users_controller.rb` | `index(18)`; `show(25)`; `new(32)`; `edit(40)`; `create(51)`; `update(62)`; `confirm(68)`; `destroy(77)`; `suspend(90)`; `reactivate(99)` |
| `app/controllers/application_controller.rb` | `(class-level)(24,25,26,27,29,30)`; `auto_complete(47,48,49,53,65)` [any, html, json]; `auto_complete_ids_to_exclude(96)`; `respond_to_not_found(191,194,195,196,197,198)` [html, js, json, xml]; `respond_to_related_not_found(203,208,209,210,211,212)` [html, js, json, xml]; `respond_to_access_denied(217,219,220,221,222,223)` [html, js, json, xml]; `redirection_url(231)` |
| `app/controllers/comments_controller.rb` | `index(21,22,26,27,28,29)` [html, json, xml]; `edit(40)`; `create(56,58)`; `update(68)`; `destroy(77)` |
| `app/controllers/confirmations_controller.rb` | `(class-level)(8)` |
| `app/controllers/emails_controller.rb` | `destroy(17)` |
| `app/controllers/entities/accounts_controller.rb` | `index(16,17,18)` [csv, xls]; `show(29)`; `new(42)`; `edit(50)`; `create(57)`; `update(71)`; `destroy(83,84,85)` [html, js]; `redraw(109,110)` [js]; `filter(120,121)` [js]; `respond_to_destroy(136)` |
| `app/controllers/entities/campaigns_controller.rb` | `index(16,17,18)` [csv, xls]; `show(31,32,38,44,49,50,53,58)` [atom, csv, html, js, rss, xls]; `new(77,81)`; `edit(89)`; `create(97)`; `update(109)`; `destroy(121,122,123)` [html, js]; `redraw(147,148)` [js]; `filter(158,159)` [js]; `respond_to_destroy(174)` |
| `app/controllers/entities/contacts_controller.rb` | `index(16,17,18)` [csv, xls]; `show(29,30)` [vcf]; `new(45,49)`; `edit(58)`; `create(65)`; `update(79)`; `destroy(89,90,91)` [html, js]; `redraw(125,126)` [js]; `respond_to_destroy(151)` |
| `app/controllers/entities/leads_controller.rb` | `index(17,18,19)` [csv, xls]; `show(29,30)` [vcf]; `new(45,49)`; `edit(59)`; `create(68)`; `update(84)`; `destroy(100,101,102)` [html, js]; `convert(115)`; `promote(125,130,131)` [json, xml]; `reject(142,143)` [html]; `redraw(180,181)` [js]; `filter(191,192)` [js]; `respond_to_destroy(217)` |
| `app/controllers/entities/opportunities_controller.rb` | `index(18,19,20)` [csv, xls]; `show(30)`; `new(44,45,47,51)`; `edit(62)`; `create(69)`; `update(92)`; `destroy(122,123,124)` [html, js]; `redraw(146,147)` [js]; `filter(155,156)` [js]; `respond_to_destroy(175)` |
| `app/controllers/entities_controller.rb` | `attach(28)`; `discard(38)`; `subscribe(47,48)` [js]; `unsubscribe(58,59)` [js]; `get_list_of_records(175)` |
| `app/controllers/home_controller.rb` | `index(18,19)` [xls]; `redraw(44,45)` [js] |
| `app/controllers/lists_controller.rb` | `create(22)`; `destroy(31)` |
| `app/controllers/passwords_controller.rb` | `(class-level)(9)` |
| `app/controllers/registrations_controller.rb` | `(class-level)(9)` |
| `app/controllers/sessions_controller.rb` | `(class-level)(9)` |
| `app/controllers/tasks_controller.rb` | `index(18,19,20,21)` [csv, xls, xml]; `show(31)`; `versions(39)`; `new(55,59)`; `edit(73)`; `create(82)`; `update(102)`; `destroy(127)`; `complete(140)`; `uncomplete(153)`; `timeline(201)` |
| `app/controllers/users_controller.rb` | `(class-level)(15)`; `show(22)`; `edit(28)`; `update(37)`; `avatar(44)`; `password(75)`; `change_password(95)`; `auto_complete(116,117)` [json] |

## How to confirm production usage

Run the audit against at least one week of production access logs, staging first when validating
the process. Count explicit format suffixes and compare user agents to distinguish the UI from
scripted consumers. The repository alone cannot decide whether XML or Atom/RSS is externally used.
