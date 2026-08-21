# Fat Free CRM — Spring Boot Target Architecture

This document describes the target-state design for rewriting Fat Free CRM (Rails 8 / Ruby 3.4)
as a Spring Boot application. It is paired with `openapi.yaml`, which captures the **current**
JSON/XML API surface of the Rails application. Nothing in this document changes the behavior of
the existing app; it is a design proposal only.

---

## 1. Current-State Route & Controller Inventory

All routes are declared in `config/routes.rb`. Response formats are governed centrally by
`app/controllers/application_controller.rb`:

```ruby
respond_to :html, only: %i[index show auto_complete]
respond_to :js
respond_to :json, :xml, except: :edit
respond_to :atom, :csv, :rss, :xls, only: :index
```

i.e. for controllers using `respond_with`:
- `index`: html, js, json, xml, atom, csv, rss, xls
- `show`: html, js, json, xml (plus `vcf` for contacts/leads)
- `edit`: js only (no json/xml)
- `new`, `create`, `update`, `destroy` and custom member/collection actions: js, json, xml
- Error handling (`respond_to_not_found`, `respond_to_access_denied`) returns 404/401 with a
  plain-text (json) or `<errors>` array (xml) body.
- CORS: a global `before_action` answers `OPTIONS` preflights and an `after_action` sets
  `Access-Control-Allow-Origin: *` on every response.
- CSRF: `protect_from_forgery with: :exception` (cookie-session based auth).

### 1.1 Authentication (Devise)

| Method | Path | Controller#action | Notes |
|---|---|---|---|
| GET | `/login` | redirect → `/users/sign_in` | legacy Authlogic compat |
| GET | `/signup` | redirect → `/users/sign_up` | legacy Authlogic compat |
| GET | `/users/sign_in` | `sessions#new` | HTML only |
| POST | `/users/sign_in` | `sessions#create` | HTML form; sets session cookie |
| DELETE/GET | `/users/sign_out` | `sessions#destroy` | redirects to sign-in |
| GET | `/users/sign_up` | `registrations#new` | only when signup allowed (`Setting.user_signup`) |
| POST | `/users` | `registrations#create` | permitted: username, email, password, password_confirmation; may create suspended user needing admin approval |
| GET | `/users/edit` | `registrations#edit` | overridden: redirects to `/profile` |
| PUT/PATCH | `/users` | `registrations#update` | Devise default |
| DELETE | `/users` | `registrations#destroy` | Devise default |
| GET | `/users/password/new` | `passwords#new` | HTML |
| POST | `/users/password` | `passwords#create` | sends reset email |
| GET | `/users/password/edit?reset_password_token=…` | `passwords#edit` | HTML |
| PUT/PATCH | `/users/password` | `passwords#update` | resets password |
| GET | `/users/confirmation/new` | `confirmations#new` | HTML |
| POST | `/users/confirmation` | `confirmations#create` | resends confirmation |
| GET | `/users/confirmation?confirmation_token=…` | `confirmations#show` | confirms account |

All four Devise subclasses declare `respond_to :html` only — the auth flow is **not** part of the
JSON/XML API. Every other controller requires an authenticated session (`authenticate_user!`),
except `home#timezone` which skips it.

### 1.2 Entity controllers (accounts, campaigns, contacts, leads, opportunities)

All five inherit `EntitiesController < ApplicationController` and are guarded by
CanCanCan `load_and_authorize_resource` (per-record access levels: Public / Private / Shared).
IDs are constrained to `/\d+/`. Common actions:

| Method | Path | Action | Formats | Notes |
|---|---|---|---|---|
| GET | `/{plural}` | index | html, js, json, xml, atom, csv, rss, xls | params: `page`, `per_page` (1–200), `query` (text + `#tag` search), `q[...]` (Ransack advanced search), `view` (brief/long/full). Session-persisted filter applies unless advanced search. |
| GET | `/{plural}/:id` | show | html, js, json, xml (+`vcf` for contacts/leads) | records a `view` version (recently-viewed) |
| GET | `/{plural}/new` | new | js | params: `related=model_id` prefill |
| GET | `/{plural}/:id/edit` | edit | js | params: `previous` |
| POST | `/{plural}` | create | js, json, xml | body: `{singular}[...]` + `comment_body`; 201/created on success, 422 with validation errors otherwise |
| PUT/PATCH | `/{plural}/:id` | update | js, json, xml | 200 or 422 |
| DELETE | `/{plural}/:id` | destroy | html, js, json, xml | 200/204 |
| PUT | `/{plural}/:id/attach` | attach (EntitiesController) | js, json, xml | params: `assets` (class name), `asset_id` |
| POST | `/{plural}/:id/discard` | discard (EntitiesController) | js, json, xml | params: `attachment` (class name), `attachment_id` |
| POST | `/{plural}/:id/subscribe` | subscribe | js, json, xml | adds current user to `subscribed_users` |
| POST | `/{plural}/:id/unsubscribe` | unsubscribe | js, json, xml | removes current user |
| GET/POST | `/{plural}/auto_complete` | auto_complete (ApplicationController) | html/js partial, json | params: `term`, `related`; json → `{results: [{id, text}]}` |
| GET | `/{plural}/redraw` | redraw | js | persists `per_page` / `sort_by` prefs, re-renders index |
| POST | `/{plural}/filter` | filter | js | stores category/status/stage filter in session, re-renders index |
| GET | `/{plural}/advanced_search` | index (Ransack `q`) | html | renders search form/results |
| GET | `/{plural}/options` | options | js | sort/naming/per-page options panel |
| GET | `/{plural}/field_group` | field_group (EntitiesController) | html partial | params: `tag`, `asset_id` |
| GET | `/{plural}/versions` | versions (EntitiesController) | js | PaperTrail history |

Entity-specific extras:

- **accounts**: member GET `contacts`, `opportunities` (js). Sidebar category totals.
- **campaigns**: member GET `leads`, `opportunities` (js). `show.xls/csv/rss/atom` render the
  campaign's **leads** list. Sidebar status totals.
- **contacts**: `show.vcf` vCard download; create/update accept nested `account`/`account_id`
  (`save_with_account_and_permissions`).
- **leads**: member GET `convert` (js), PUT/PATCH `promote` (converts to Contact + optional
  Account/Opportunity; 422 with combined errors on failure), PUT `reject`; collection GET
  `autocomplete_account_name`; `show.vcf`. Sidebar status totals. Create uses
  `save_with_permissions(params.permit!)`.
- **opportunities**: member GET `contacts` (js); create/update accept nested `account`
  (`save_with_account_and_permissions`); index sorted with `weighted_sort`. Sidebar stage totals.

### 1.3 Polymorphic / support controllers

| Method | Path | Action | Formats | Notes |
|---|---|---|---|---|
| GET | `/comments?{entity}_id=N` | comments#index | html (redirect to asset), js, json, xml | lists comments for one commentable |
| POST | `/comments` | comments#create | js, json, xml | body: `comment[commentable_type, commentable_id, private, title, comment, state]`; 404 if commentable inaccessible |
| GET | `/comments/:id/edit` | comments#edit | js | |
| PUT/PATCH | `/comments/:id` | comments#update | js, json, xml | |
| DELETE | `/comments/:id` | comments#destroy | js, json, xml | |
| DELETE | `/emails/:id` | emails#destroy | js, json, xml | only action exposed |
| GET | `/tasks?view=pending|assigned|completed` | tasks#index | html, js, json, xml, xls, csv | returns tasks **grouped by bucket** (`{overdue: [...], due_today: [...]}`); xml excludes `subscribed_users` |
| GET | `/tasks/:id` | tasks#show | html, json, xml | |
| GET | `/tasks/new`, `/tasks/:id/edit` | new / edit | js | `related=asset_type_id` |
| POST | `/tasks` | tasks#create | js, json, xml | `task[...]` incl. `bucket`, `due_at`, `calendar`, `asset_type/asset_id` |
| PUT/PATCH | `/tasks/:id` | tasks#update | js, json, xml | |
| DELETE | `/tasks/:id` | tasks#destroy | js, json, xml | param `bucket` for UI bookkeeping |
| PUT | `/tasks/:id/complete` | complete | js, json, xml | sets `completed_at/completed_by` |
| PUT | `/tasks/:id/uncomplete` | uncomplete | js, json, xml | clears them |
| GET | `/tasks/:id/versions` | versions | js | |
| POST | `/tasks/filter` | filter | js | params: `filter`, `checked`, `view` (session-stored) |
| GET/POST | `/tasks/auto_complete` | auto_complete | js/json | |
| GET/POST/PUT... | `/lists` | lists#create | js, json, xml | upserts saved-search list by name; `list[name,url]`, `is_global` |
| CRUD | `/lists/:id` | lists (full `resources`) | | only `create`/`destroy` implemented; others 501/missing template |
| DELETE | `/lists/:id` | lists#destroy | js, json, xml | |

### 1.4 Home / dashboard

| Method | Path | Action | Notes |
|---|---|---|---|
| GET | `/` , `/activities` | home#index | activity stream (PaperTrail versions), html/js/json/xml/xls |
| GET | `/home/options` | home#options | js panel |
| POST | `/home/redraw` | home#redraw | persists activity prefs (`asset`, `event`, `user`, `duration`), js |
| GET | `/home/toggle?id=…` | home#toggle | session toggle state, `204/200 head :ok` |
| GET/PUT/POST | `/home/timeline` | home#timeline | expands/collapses comments & emails (`id`, `type`, `state`), `head :ok` |
| GET/PUT/POST | `/home/timezone?offset=…` | home#timezone | **unauthenticated**; stores tz offset in session, `head :ok` |

### 1.5 Users (profile)

`resources :users, except: %i[index destroy create]` + devise_scope index/show:

| Method | Path | Action | Formats |
|---|---|---|---|
| GET | `/users/:id`, `/profile` | users#show | html, js, json, xml |
| GET | `/users/:id/edit` | users#edit | js |
| PUT/PATCH | `/users/:id` | users#update | js, json, xml (profile fields incl. social handles, notification prefs) |
| GET | `/users/:id/avatar` | users#avatar | js |
| PUT/PATCH | `/users/:id/upload_avatar` | users#upload_avatar | multipart (`avatar[image]` or `gravatar=1`), responds to parent iframe |
| GET | `/users/:id/password` | users#password | js |
| PATCH | `/users/:id/change_password` | users#change_password | js, json, xml; requires `current_password` |
| POST | `/users/:id/redraw` | users#redraw | js — sets locale, reloads |
| GET | `/users/opportunities_overview` | users#opportunities_overview | js |
| GET/POST | `/users/auto_complete` | users#auto_complete | json array of `"Full Name (@username)"` strings |

### 1.6 Admin (requires `user.admin?`, else redirect to root)

| Method | Path | Action | Notes |
|---|---|---|---|
| GET | `/admin` | admin/users#index | alias |
| GET | `/admin/users` (+`.xml`) | index | paginated, Ransack `q`, `query` text search |
| GET | `/admin/users/:id` (+`.xml`) | show | |
| GET | `/admin/users/new`, `/:id/edit`, `/:id/confirm` | new/edit/confirm | js |
| POST | `/admin/users` (+`.xml`) | create | `user[...]` incl. `admin`, `password`, `group_ids` |
| PUT/PATCH | `/admin/users/:id` (+`.xml`) | update | |
| DELETE | `/admin/users/:id` (+`.xml`) | destroy | refuses if user has assets / is current user |
| PUT | `/admin/users/:id/suspend`, `/:id/reactivate` | suspend/reactivate | |
| GET/POST | `/admin/users/auto_complete` | auto_complete | html partial |
| GET | `/admin/leads` | admin/leads#index | CSV import UI |
| POST | `/admin/leads/import` | import | multipart `file` (CSV), redirects |
| CRUD | `/admin/groups[/:id]` | admin/groups | `group[name, user_ids[]]` |
| CRUD (no index/show) | `/admin/field_groups[/:id]` (+ POST `sort`, GET `:id/confirm`) | admin/field_groups | custom-field groups |
| CRUD | `/admin/fields[/:id]` (+ `auto_complete`, `options`, `redraw`, POST `sort`, GET `subform`) | admin/fields | custom fields; also aliased as `custom_fields`/`core_fields` |
| CRUD (no show) | `/admin/tags[/:id]` (+ GET `:id/confirm`) | admin/tags | |
| CRUD (no show) | `/admin/research_tools[/:id]` | admin/research_tools | `research_tool[name, url_template, enabled]` |
| GET | `/admin/settings` | admin/settings#index | |
| PUT | `/admin/settings` | admin/settings#update | large nested settings payload; redirects |
| GET | `/admin/plugins` | admin/plugins#index | |

---

## 2. Proposed Spring Boot Architecture

### 2.1 Technology baseline

- Java 21, Spring Boot 3.x, Maven or Gradle
- Spring Web (REST controllers), Spring Data JPA (Hibernate) + PostgreSQL, Flyway migrations
- Spring Security (session or JWT — see §2.4), springdoc-openapi (serves `openapi.yaml`)
- MapStruct for entity↔DTO mapping, Bean Validation (Jakarta) for request validation
- Envers or a custom audit table to replace PaperTrail; Spring Batch or plain services for CSV import/export

### 2.2 Layering

```
com.fatfreecrm
├── api/                    # @RestController classes (thin; HTTP concerns only)
│   ├── AccountController, CampaignController, ContactController,
│   │   LeadController, OpportunityController        (entity CRUD + custom ops)
│   ├── TaskController, CommentController, EmailController, ListController
│   ├── UserController, AuthController, HomeController (activity stream)
│   └── admin/ (AdminUserController, AdminGroupController, AdminFieldController,
│               AdminFieldGroupController, AdminTagController,
│               AdminResearchToolController, AdminSettingController,
│               AdminLeadImportController, AdminPluginController)
│   └── dto/                # request/response records + MapStruct mappers
├── service/                # @Service: business rules, transactions
│   ├── AccountService … OpportunityService
│   ├── LeadPromotionService        # Lead → Contact/Account/Opportunity conversion
│   ├── TaskService (bucket computation), CommentService,
│   │   SubscriptionService (subscribe/unsubscribe), AttachmentService (attach/discard)
│   ├── SearchService (Ransack-equivalent: spec-based filtering + text/tag search)
│   ├── ActivityService (audit trail / recently-viewed), ExportService (csv/xls/vcf/rss/atom)
│   ├── SettingService (DB > yaml default hierarchy), CustomFieldService
│   └── admin/ (UserAdminService, LeadImportService, …)
├── repository/             # Spring Data JPA interfaces + Specifications
│   ├── AccountRepository … OpportunityRepository, TaskRepository,
│   │   CommentRepository, EmailRepository, ListRepository, UserRepository,
│   │   GroupRepository, PermissionRepository, TagRepository, FieldRepository,
│   │   FieldGroupRepository, SettingRepository, VersionRepository (audit)
├── domain/                 # JPA entities mirroring db/schema.rb
├── security/               # AccessLevel evaluator (Public/Private/Shared), admin guard
└── config/                 # CORS, i18n, OpenAPI, pagination defaults
```

Controller → Service → Repository, one direction only. Controllers never touch repositories;
services own transactions (`@Transactional`) and cross-entity workflows (e.g. lead promotion).

### 2.3 Domain mapping

| Rails model | Spring entity | Notes |
|---|---|---|
| Account, Campaign, Contact, Lead, Opportunity | same names | shared `@MappedSuperclass CrmEntity` (user, assignedTo, access, background_info, subscribedUsers, soft-delete `deletedAt`) replacing Rails STI-ish shared columns |
| Task | Task | `bucket` computed in `TaskService` (overdue / due_today / …), polymorphic `asset` via (`assetType`, `assetId`) |
| Comment, Email | Comment, Email | polymorphic commentable/mediator via (type, id) pair — keep as discriminator columns, not JPA inheritance |
| Address | Address | `addressable` polymorphic; embed billing/shipping on Account |
| Permission, Group | Permission, Group | drives Shared access |
| Preference | UserPreference | key/value per user (per_page, sort_by, naming, activity filters) |
| Setting | Setting | hierarchy: DB row > `application.yml` default |
| Field, FieldGroup, CustomFieldPair | Field, FieldGroup | **do not** replicate Rails' dynamic `ALTER TABLE` custom columns; store custom values in a JSONB column per entity (`custom_fields jsonb`) with metadata in `fields` |
| PaperTrail Version | AuditEvent | Envers or custom table; feeds activity stream and `versions` endpoints |
| Tag/Tagging (acts-as-taggable) | Tag + join tables | |
| List | SavedList | name + URL of saved search |

Soft delete (`deleted_at`) implemented with `@SQLDelete`/`@Where` or explicit repository filters.

### 2.4 Authentication & authorization

Current state: Devise cookie sessions, CSRF token, optional signup with admin approval,
email confirmation, password reset; CanCanCan + custom access levels
(Public / Private / Shared-with-users-and-groups) enforced per record via the `permissions` table;
admin namespace requires `admin` flag.

Target:
- **Spring Security with stateless JWT** (access + refresh tokens) for the JSON API:
  `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`.
  This replaces the Devise session endpoints; HTML form login disappears with the Rails views.
  (If a server-rendered or same-origin SPA front end is chosen instead, session cookies +
  CSRF via Spring Security's default filter chain are an acceptable alternative; the service
  layer is agnostic.)
- Registration/confirmation/password-reset become JSON endpoints backed by
  `UserAccountService` + mail sender, preserving the `Setting.user_signup` modes
  (not allowed / needs approval / allowed).
- Record-level access: a custom `PermissionEvaluator` reproducing `Model.my(user)` scoping —
  `access = 'Public' OR user_id = :me OR assigned_to = :me OR EXISTS (permission for user/group)`.
  Applied both as JPA Specifications (list queries) and `@PreAuthorize("hasPermission(...)")`
  (single-record access).
- Admin endpoints: `@PreAuthorize("hasRole('ADMIN')")` on the `admin/` controllers.
- CORS: replicate current permissive policy (`*`) via `CorsConfigurationSource`, tightening
  origins per deployment.

### 2.5 Mapping Rails `respond_to` formats to a JSON API

The Rails app serves four families of responses; the rewrite collapses them:

| Rails format | Current purpose | Spring Boot equivalent |
|---|---|---|
| `html` | server-rendered pages | Out of scope for the API; a separate SPA (or Thymeleaf app) consumes the JSON API |
| `js` (`*.js.haml`) | jQuery/UJS snippets that mutate the page (redraw lists, close modals, update sidebars) | **Replaced by JSON.** Every `.js` action returns the data the script used to render: e.g. `accounts#filter`/`#redraw` → `GET /api/v1/accounts?category=…&sortBy=…&perPage=…` returning `{items, page, totalCount, sidebarTotals}`. Session-stored filters/sort prefs become explicit query params persisted in `UserPreference` via the API. Modal-opening actions (`new`, `edit`, `options`, `convert`, `confirm`) disappear; the client fetches form metadata from `GET /api/v1/metadata/{entity}` (field groups, custom fields, select options from Settings). |
| `json` / `xml` | machine API (ActiveModel `to_json`/`to_xml` of the record or errors) | Kept as the primary contract (see `openapi.yaml`). XML support optional via Jackson XML if consumers exist; recommendation: JSON-only v1, document XML as deprecated. |
| `atom`, `rss`, `csv`, `xls`, `vcf` | index feeds & exports | `ExportService` + content negotiation on the same list endpoints (`Accept: text/csv`, `?format=csv`), Apache POI for XLS, ez-vcard for vCards, Rome for Atom/RSS if still required. |

Concrete AJAX→JSON mappings:

- `POST /{entity}/filter`, `GET /{entity}/redraw` → `GET /api/v1/{entity}` with query params
  (`status`, `category`, `stage`, `sortBy`, `perPage`, `page`, `view`); sidebar totals returned in
  the list envelope (`facets`).
- `GET /{entity}/auto_complete` → `GET /api/v1/{entity}/autocomplete?term=…&excludeRelated=…`
  returning `{results:[{id,text}]}` (already JSON today — contract preserved).
- `PUT /{id}/attach`, `POST /{id}/discard` → `POST /api/v1/{entity}/{id}/attachments` /
  `DELETE …/attachments` with `{assetType, assetId}`.
- `POST /{id}/subscribe|unsubscribe` → `PUT/DELETE /api/v1/{entity}/{id}/subscription`.
- `PUT /leads/{id}/promote` → `POST /api/v1/leads/{id}/promotion` returning the created
  contact/account/opportunity, 422 with field errors on failure.
- `GET /home/toggle`, `/home/timeline`, session view prefs → client-side state or
  `PATCH /api/v1/me/preferences`.
- `tasks#index` grouped response → `GET /api/v1/tasks?view=pending` returning
  `{buckets: {overdue: [...], due_today: [...] }}` (matches current JSON shape).

### 2.6 Error contract

Standardize on RFC 9457 problem+json:
- 401 (unauthenticated), 403 (CanCan denied — today rendered as 401 "not authorized" text; the
  rewrite should use 403 and note the delta), 404 (record not found — today plain-text/xml
  message), 422 validation errors as `{errors: {field: [messages]}}` mirroring ActiveModel.

### 2.7 Migration strategy

1. **Contract first**: freeze `openapi.yaml` (current behavior) as the compatibility baseline.
2. **Strangler**: put a gateway in front of Rails; implement read-only endpoints
   (index/show/autocomplete) in Spring Boot against the same PostgreSQL schema; shift traffic
   per-resource.
3. Port write paths per aggregate (comments/tasks first — smallest blast radius; entities next;
   lead promotion last among entities), then admin, then auth cut-over from Devise sessions to JWT.
4. Replace dynamic custom-field columns with JSONB behind a dual-read shim during transition.
5. Retire `.js` responses only after the SPA replaces the jQuery/UJS front end; until then the
   Rails app keeps serving HTML/JS while Spring Boot serves `/api/v1`.

Out of scope for the API rewrite but required for feature parity: IMAP mail processing
(`lib/fat_free_crm/mail_processor`) → Spring Integration mail adapters; background jobs
(Solid Queue) → Spring's `@Scheduled`/JobRunr; mailers → `JavaMailSender` + templates.
