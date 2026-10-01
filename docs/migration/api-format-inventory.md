# API response-format inventory (Phase 0 consumer audit)

Input to [`decisions/0001-api-response-format-scope.md`](decisions/0001-api-response-format-scope.md).
Everything below is read off the current codebase at the tagged baseline commit; the
"external consumer?" column is what still needs confirmation from whoever operates the
deployment (server logs are the source of truth — see [How to confirm](#how-to-confirm-usage)).

## Where formats are declared

`app/controllers/application_controller.rb` declares the whole format surface once, for
every controller that inherits it:

```ruby
respond_to :html, only: %i[index show auto_complete]
respond_to :js
respond_to :json, :xml, except: :edit
respond_to :atom, :csv, :rss, :xls, only: :index
```

So *any* `index` action of *any* entity controller answers to
`.html .js .json .xml .atom .rss .csv .xls`, and every non-`edit` action answers to
`.json` and `.xml`, whether or not a template exists. That breadth is an artifact of the
inherited declaration, not evidence of use.

## Format-by-format inventory

| Format | Mime | How it is produced | Actions | Implementation | External consumer? |
|---|---|---|---|---|---|
| `html` | `text/html` | 354 Haml templates | index, show, auto_complete | Rails views | Yes — the product UI |
| `js` | `text/javascript` | server-generated JS (`responds_to_parent2`) | nearly all | Haml `.js` templates | No — internal to the UI only |
| `json` | `application/json` | `respond_with` → `to_json` on AR objects | all except `edit` | ActiveModel serialization | **Assumed yes** — the `/api/v1` baseline in `openapi.yaml` |
| `xml` | `application/xml` | `respond_with` → `to_xml` on AR objects | all except `edit` | ActiveModel serialization | **Unknown — must confirm** |
| `atom` | `application/atom+xml` | `app/views/application/index.atom.builder`, `show.atom.builder`, `home/index.atom.builder` | index (+ show template exists but is unreachable via `respond_to`) | `atom_feed` builder | **Unknown — must confirm** |
| `rss` | `application/rss+xml` | `app/views/application/index.rss.builder`, `show.rss.builder`, `home/index.rss.builder` | index | builder | **Unknown — must confirm** |
| `csv` | `text/csv` | `render csv:` custom renderer (`lib/fat_free_crm/renderers.rb` → `FatFreeCRM::ExportCSV.from_array`) | index (accounts, campaigns, contacts, leads, opportunities, tasks) | all columns except `password`/`token`, plus `tags`; **pagination disabled** | Yes — user-facing export |
| `xls` | `application/vnd.msexcel` (registered in `config/initializers/mime_types.rb`) | 7 `index.xls.builder` templates + `layouts/header.xls.builder` | index (accounts, campaigns, contacts, leads, opportunities, tasks, home) | **SpreadsheetML 2003 XML**, not a real `.xls` binary; column list hard-coded per template with `I18n.t` headers; **pagination disabled** | Yes — user-facing export |
| `vcf` | `text/x-vcard` | `helpers.vcard_for` (`vcardigan` gem) via `send_data` | `contacts#show`, `leads#show` | vCard 4.0 text | Yes — user-facing single-record export |

Notes that matter for the port:

- **CSV and XLS are not the same projection.** CSV is generic (every non-secret column,
  reflected at runtime, so it silently includes `cf_*` custom fields); XLS is a curated,
  translated, hard-coded column list per entity. A Java implementation must reproduce both
  shapes, not unify them.
- **CSV/XLS ignore pagination** (`EntitiesController#get_list_of_records`), so they stream the
  full authorized result set. Any Spring Boot equivalent needs streaming, not a page fetch.
- **Atom/RSS are per-user, authenticated feeds** — the builder embeds `current_user` as the feed
  author and relies on session auth, so a feed reader can only consume them with a session
  cookie. This makes accidental external consumption unlikely but not impossible.
- **CORS is wide open** (`Access-Control-Allow-Origin: *` in `ApplicationController`), so
  browser-based external consumers are possible and would not appear in any allow-list.
- `xml` error bodies exist too (`respond_to_not_found`, `respond_to_access_denied`, and the
  combined lead-promotion 422 in `leads_controller.rb`), so dropping XML changes error
  responses, not just success bodies.

## How to confirm usage

The audit cannot be completed from the repository alone — it needs one week of production
access logs. Extract format usage by suffix and `Accept` header:

```bash
# Rails/nginx access log: count requests by explicit format suffix
grep -oE '"[A-Z]+ [^" ]+' access.log \
  | grep -oE '\.(json|xml|atom|rss|csv|xls|vcf)(\?|$)' \
  | sort | uniq -c | sort -rn

# Same, split by user agent to separate the UI from scripted consumers
awk -F'"' '$2 ~ /\.(xml|atom|rss)/ {print $6}' access.log | sort | uniq -c | sort -rn
```

Decision rule agreed for Phase 0: a format is **kept** if it has non-UI traffic from more than
one distinct user agent, or a named internal consumer; otherwise it is **deprecated** with a
sunset header and dropped after the announced window.
