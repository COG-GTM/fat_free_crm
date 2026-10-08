# ADR: Write API foundation + comments/emails/tasks/lists (AB-272 Phase A)

**Status**: proposed. ARB ticket: TO BE CREATED.

## Context

Epic AB-261 requires the Spring Boot service (`spring/`) to reach contract-diff
parity with the Rails monolith. Read families landed in AB-266/AB-270; Phase A of
AB-272 adds the first write paths, so a shared write foundation must exist that
every later family reuses without re-deciding statuses, validation rendering,
versioning or authz. Live Rails responses are the ground truth; every semantic
below was verified against the running app.

## Decision

- **Param envelope**: Rails wraps attributes in `{"<model>": {...}}` plus
  controller extras (`bucket`, `view`, `is_global`). DTOs keep that shape and
  `RailsParams` performs Rails-flavoured casts (`"abc"`→nil int, `"1"`→true,
  ISO→instant), so client payloads are identical against both stacks.
- **Statuses/bodies**: POST → 201 + resource JSON + `Location`; PUT/DELETE → 204
  empty; validation → 422 with `{"errors": {attr: [message]}}` — `errors.as_json`
  raw messages, caret text verbatim (e.g. `{"name": ["^Please specify task name."]}`),
  NOT `full_message`. RecordNotFound → 404 text/plain; CanCan denied → 401 in
  Rails, 403 in Spring (allow-listed `authz-denied-401-vs-403`).
- **Validation catalog**: generated JSON from the real Rails I18n
  (`rake ffcrm:migration:activemodel_messages` →
  `spring/src/main/resources/validation/activemodel_en_US.json`), CI drift check,
  ActiveModel lookup chain in `ActiveModelMessages`.
- **Versions**: `VersionRecorder` + `PaperTrailYaml` produce PaperTrail rows
  byte-identical to Psych output in the same transaction. Verified byte-level
  facts: `object` for updates leads with save-assigned changed attributes
  (assignment order, before values) then remaining columns in column order;
  `object_changes` is column-order `[old, new]` pairs; nil scalars are `key:` /
  `-`; empty string is `''`; `&N`/`*N` anchors only for objects seen more than
  once; timestamp-only saves write no version.
- **Authz**: reuse `hasPermission`/`CrmAccessPolicy`; one foundation extension —
  `RailsResources.savedList`. Rails security gaps are mirrored, not fixed.
- **Gateway**: append-only `__spring_w/<family>` write blocks (server-level `if`
  on write methods → internal rewrite), disabled by default, GET paths
  byte-identical.
- **Harness**: `reset: true` (TRUNCATE + snapshot replay) and `dbAssert`
  (post-response row diff incl. `versions`) in `contractTest`; `dualWriteSoak`
  Gradle task (excluded from `contractTest`) for concurrent cross-stack writes.

## Callback parity table

| Rails callback | File:line | Spring equivalent | Status |
| --- | --- | --- | --- |
| `Task has_paper_trail meta:{related: :asset}, ignore:[:subscribed_users]` | app/models/polymorphic/task.rb:112-113 | `PaperTrailOptions` Task entry | Ported |
| `Comment has_paper_trail meta:{related: :commentable}, ignore:[:state]` | app/models/polymorphic/comment.rb:31 | `PaperTrailOptions` Comment entry | Ported |
| `Email has_paper_trail meta:{related: :mediator}, ignore:[:state]` | app/models/polymorphic/email.rb:36 | `PaperTrailOptions` Email entry | Ported |
| CRM entities `ignore:[:subscribed_users]` | account.rb:68, campaign.rb:56, contact.rb:90, lead.rb:68, opportunity.rb:76 | `PaperTrailOptions` defaults; subscribed_users ignored on commentable versions | Ported |
| `validates_presence_of :user, :name(missing_task_name), :calendar(if specific_time && !completed_at)`; `validate :specific_time, unless: :completed?` | app/models/polymorphic/task.rb:117-120 | `TaskWriteService#validate` (500 `RailsInternalError` on `Time.parse(nil)` TypeError) | Ported, incl. Rails 500 |
| `before_create :set_due_date`; `before_update :set_due_date, unless: :completed?` | app/models/polymorphic/task.rb:122-123 | `TaskWriteService#applySetDueDate` | Ported |
| `before_save :notify_assignee` | app/models/polymorphic/task.rb:124, body task.rb:273-277 | — | Ported as no-op (Rails body is a comment-only stub) |
| `TasksController create/update/destroy/complete/uncomplete` via `Task.tracked_by` | app/controllers/tasks_controller.rb:78,91,118,132,145 | `TasksWriteController` + `trackedTask` spec | Ported |
| `TaskObserver#after_update` complete/reassign/reschedule via `log_activity` | app/models/observers/task_observer.rb:20-30 | `TaskWriteService#recordUpdateVersions` (`recordEvent`, NULL object/changes/related) | Ported |
| `CommentsController create/update/destroy` (`load_and_authorize_resource`, `model.my(current_user).find_by_id` → 404) | app/controllers/comments_controller.rb:47,66,75 | `CommentsWriteController` + `CommentWriteService#findCommentable` | Ported |
| `validates_presence_of :user, :commentable, :comment` | app/models/polymorphic/comment.rb:30 | `CommentWriteService#validate` | Ported |
| `Comment#before_create :subscribe_mentioned_users` (`@username` exact-match) | app/models/polymorphic/comment.rb:34,63-68 | `CommentWriteService#subscribeMentions` | Ported |
| `Comment#after_create :subscribe_user_to_entity` (`commentable.subscribed_users << id; commentable.save`) | app/models/polymorphic/comment.rb:35,48-51 | `CommentWriteService#subscribe` (FOR UPDATE lock on commentable) | Ported |
| `Comment#after_create :notify_subscribers` (SubscriptionMailer) | app/models/polymorphic/comment.rb:35,54-58 | — | Deliberately skipped → AB-273 jobs/mail |
| `EntityObserver` after_create/after_update → `UserMailer.assigned_entity_notification` on commentable.save | app/models/observers/entity_observer.rb:11-16 | `CommentWriteService#commentableSaveValid` reproduces the *save* (validations + set_due_date) but sends no mail | Deliberately skipped → AB-273 jobs/mail |
| `ListsController#create`/`destroy` (lower(name)+user_id upsert, `is_global!="1"`→current user, destroy has no authz) | app/controllers/lists_controller.rb:11,27 | `ListsController` + `ListWriteService` | Ported (incl. missing authz) |
| `List validates_presence_of :name, :url`; no paper trail | app/models/list.rb:9-10 | `ListWriteService` 422s; no `List` version rows | Ported |
| `EmailsController#destroy` | app/controllers/emails_controller.rb:15 | `EmailsController` + `EmailWriteService` | Ported |

## Alternatives considered

- **Envers / Hibernate listeners**: rejected — `versions` column shape
  (`object`/`object_changes` Psych YAML, `meta:` related, `ignore:` lists,
  observer event rows) is PaperTrail-specific; no generic audit library renders
  byte-identical output.
- **Full-entity UPDATE instead of `@DynamicUpdate`**: rejected — Rails partial
  updates emit changed-column SQL; a full UPDATE would rewrite untouched columns
  and diverge in lock/version behaviour.
- **Separate write database / CDC fan-out**: rejected — the migration contract
  requires same-transaction dual-readability against the shared Rails schema.
- **Gateway by HTTP method vs per-path blocks**: chose per-path `__spring_w/`
  internal rewrite blocks — method-level routing would break Rails' mixed
  read/write paths and would not stay append-only for GET parity.

## Non-functional requirements

- Audit versions write in the same transaction as the entity change (no lost
  audit trail on rollback).
- Extended soak (`./gradlew dualWriteSoak`, ≥5 min): concurrent cross-stack task
  updates, complete/uncomplete toggles and comment creates on a shared
  commentable; asserts 0 5xx, 0 constraint violations, 0 lost task-field
  updates, version-per-(item_type,event) == successful writes, comment rows ==
  creates. The Rails `subscribed_users` read-modify-write race on the shared
  commentable is measured and reported, not asserted (Spring takes FOR UPDATE).
- Latency: writes add at most one version INSERT + optional commentable lock;
  no synchronous mailers are ported (AB-273).

## Security

- Authz per endpoint mirrors Rails: task writes scoped by `Task.tracked_by`,
  comments by `load_and_authorize_resource` + commentable visibility,
  emails destroy by owner, lists destroy intentionally unauthenticated-scope
  (mirrored gap, lists_controller.rb:27).
- Mirrored Rails gaps (documented, not fixed): task create does not force
  `user_id` (tasks_controller.rb:78-89), comment params permit
  `user_id`/`commentable_*` reassignment (comment.rb `comment_params`,
  comments_controller.rb `comment_params` allow-list), list destroy without
  authz, and `is_global=1` with another user's `user_id` lets `ListsController#create`
  overwrite that user's same-name list (lists_controller.rb:11). Tracked as open questions for the epic.
- No secrets/tokens in write JSON (`authentication_token` excluded by
  RailsJsonWriter); CSRF remains a Rails-side concern only (Spring API is
  bearer-token, stateless).

## Ownership and rollout

- Gateway write blocks ship disabled; each family is enabled/rolled back
  independently via `spring/gateway/routing.sh enable|disable <family>-writes`.
- Contract harness resets between write cases; CI contract-diff job runs the
  case suite and a 1-minute soak (`SOAK_MINUTES=1 ./gradlew dualWriteSoak`).

## New dependencies

- None at runtime. `contractTest` gains `org.postgresql:postgresql`
  (runtimeOnly) and `spotbugs-annotations` compileOnly 4.9.3 (existing version).

## Rails JSON surfaces not ported (out of scope)

- `CommentsController#edit`, `#new` (JS-only), `TasksController#filter`,
  `#new`, `#edit`, `#versions` (JS-only), `ListsController` GETs (none exist in
  Rails), `EmailsController` non-destroy actions (none exist in Rails),
  `Task#versions` JSON view.

## Consequences

Every subsequent write family (AB-272 phases B+) follows the "Adding a write
family" recipe in `spring/README.md` with no new foundation decisions. Mirrored
Rails gaps remain open questions, deliberately not fixed.

## Phase B: entities (accounts, campaigns, contacts, leads, opportunities)

`EntityWriteService` + `EntitiesWriteController` implement the five CRM write
families under `/api/v1`: `POST /{f}`, `PUT /{f}/{id}`, `DELETE /{f}/{id}`,
`PUT /{f}/{id}/attach`, `POST /{f}/{id}/discard`, `POST /{f}/{id}/subscribe`,
`POST /{f}/{id}/unsubscribe`, plus lead `GET /{id}/convert`,
`PUT|PATCH /{id}/promote`, `PUT /{id}/reject`. Member writes use
`hasPermission(#id,'<Model>','update'|'destroy')` (nonexistent → 404,
out-of-scope → 403 vs Rails 401, covered by the existing
`authz-denied-401-vs-403` allow-list entry).

Rails semantics mirrored (verified live on the contract corpus):

- `resource_params` is `permit!` — every model key is assignable, including
  `user_ids`/`group_ids`/`tag_list`/`cf_*`/counters/FKs.
- CanCan `attributes_for` fills `access: 'Public'`, `user_id`, `assigned_to`
  from the current user only when the key is absent from the payload
  (verified: `access: Private` + `user_id: 3` persisted when `assigned_to` fell
  back to the requester — mirrored gap, not fixed).
- Updates assign `access` before the rest of the params; `access=` and
  `user_ids=`/`group_ids=` delete permission rows immediately — the deletes
  commit (REQUIRES_NEW) even when the entity save then 422s (verified:
  `PUT /accounts/103 {access: Public, category: bogus}` → 422, permission row
  gone, `access` column stays `Shared`).
- `subscribe` appends `subscribed_users` under a FOR UPDATE lock, persists via
  `entity.save`, then 500s on `respond_with(@entity)`'s nil ivar
  (`UrlGenerationError "Nil location provided. Can't build URI."`, verified);
  `unsubscribe` → 201 + entity JSON; `attach` → 204; `discard` → 201 + entity
  JSON and deletes join rows with no callbacks (no counter decrement, no
  destroy version, verified).
- Join writes: `AccountContact` create writes a version with
  `meta: {related: :contact}` and bumps `contacts_count`; `AccountOpportunity`
  writes a version (no meta) and bumps `opportunities_count`;
  `ContactOpportunity` has no paper trail (commented out in Rails).
- Campaign attach/discard of leads/opportunities goes through
  `update_attribute(:campaign, ...)` → a Lead/Opportunity update version, plus
  counter-cache `leads_count`/`opportunities_count` via `update_counters`
  (no `updated_at` bump, no version of its own).
- `update_with_lead_counters` compares `campaign_id` to the raw param:
  a JSON number compares equal to the cast integer (same-campaign path); a
  JSON string never does (decrement+increment drift path) — mirrored.
- `save_with_permissions`: `campaign` param → `Campaign.find` (404); `access:
  'Campaign'` + a campaign copies campaign access + permission rows (verified:
  Shared campaign copies to a Shared lead with the same permission rows);
  `access: 'Campaign'` with no campaign persists the raw string (verified).
- Contact `save_with_account_and_permissions` on update: an absent
  `account` key unlinks the account (join row destroyed, verified);
  `account: {id}` → `Account.find` unscoped (404, verified); `account: {name}`
  → unscoped `find_by(name:)` else `Account.new(params)` with
  `user = model.user` (gaps mirrored).
- Opportunity `save_with_account_and_permissions` requires `params[:account]`
  — absent → `params[:account][:id]` NoMethodError → 500 (verified).
- `promote` is NOT one transaction; a failed promote renders
  `@account.errors + @opportunity.errors + @contact.errors` — `Errors#+`
  does not exist on Rails 8 → 500 (verified). Because each `save` commits
  independently, a partial promote leaves an orphan Account/Opportunity
  (verified: `access: Shared` + no user_ids fails `contact.save`, account and
  opportunity persist with their create versions). `promoteLead` therefore has
  no wrapping transaction. Success → 204 after
  `update_attribute(:status, 'converted')`; `params[:access]` lands on the
  contact only (verified).
- `tag_list` replaces 'tags'-context taggings with NULL tagger and maintains
  `tags.taggings_count`; `comment_body` → `comments.create` via the Phase A
  comment service (author subscribed); `cf_*` → AB-271 JSONB writes.

Skipped (AB-273 jobs/mail): `enqueue_website_job`/`enqueue_wikidata_job`
(account.rb:86-87), comment subscriber notifications, `update_recently_viewed`
(entities_controller.rb:186-188 is show-only anyway).

Callbacks ported: presence/inclusion/uniqueness/dates validations in Rails
declaration order, `users_for_shared_access`, `nullify_blank_category`,
`require_*`/`unroll` Setting reads (YAML fallback), counter callbacks,
belongs_to required (`contact.user` → "must exist"), lengths-from-database,
observer stage→probability (won→100, lost→0).

Shared-file edits were append-only: PaperTrailOptions/VersionRecorder gained
AccountContact/AccountOpportunity/Address trails; repositories gained scoped
finders; nginx gained five append-only `<family>-writes` gateway blocks;
routing.sh gained the matching block names.

## Phase B: admin

Scope: every JSON-reachable write in `app/controllers/admin/` — users (create/update/destroy/
suspend/reactivate), groups, tags, research tools, field groups (incl. sort), custom fields
(incl. sort and runtime DDL) and settings — behind `@AdminOnly` in `AdminWriteController`,
following the "Adding a write family" recipe. Gateway block `admin-writes` (disabled by default).

Decisions:

- **Rails failures after commit are reproduced.** Group/tag/field-group/field create and the
  two sort actions commit and then raise in Rails (missing `*_url` helpers / templates); Spring
  commits the same rows and returns 500. Clients cannot distinguish less than they can in Rails.
- **Runtime DDL is ported, not redesigned.** Custom-field create/retype issues the same
  `ADD COLUMN`/`ALTER COLUMN TYPE` as Rails on the shared table inside the write transaction;
  destroy keeps the column (Rails does). Flyway remains additive-only because runtime columns are
  not schema migrations; Hibernate `validate` ignores unmapped columns; `prepareThreshold=0`
  avoids pgjdbc cached-plan failures; `CustomFieldRegistry` is invalidated on commit so the
  AB-271 JSONB design keeps reading current metadata.
- **Settings YAML** is produced by `RubyYaml` (Psych-compatible) rather than a YAML library so
  `settings.value` bytes match Rails exactly.
- **User passwords** use the AB-264 legacy-hash encoder (Authlogic sha512 digest + salt), so
  users created or re-passworded by either stack can sign in on both.

Callbacks ported: `User` email strip/downcase, `suspend_if_needs_approval`, Devise reconfirmable
(`unconfirmed_email`, `confirmation_token`, `confirmation_sent_at`), `destroyable?` guards,
`has_paper_trail ignore: [:last_sign_in_at]`, dependent deletes (avatars, permissions,
preferences, groups_users); `FieldGroup` name derivation and fields move to `custom_fields` on
destroy; `Field` `acts_as_list` (unscoped) repositioning; `CustomField` `set_name`,
`add_column`, `update_column`, `validate_change`; `CustomFieldPair` create/update pairing (implemented from the Rails source; no contract case yet);
`Tag` `dependent: :destroy` taggings. Skipped: Devise confirmation/notification mail delivery,
`Setting` in-process cache clear (AB-273 owns the cache — eviction point marked in
`AdminSettingsWriteService`).

Mirrored Rails gaps (deliberately not fixed):

- Destroy of a blocked user (self / owns records) answers 204 with no change and no error.
- Destroying a tag leaves `field_groups.tag_id` dangling.
- Research tools have no validations (blank name/URL template accepted).
- Group/tag names are unique case-sensitively only.
- `group_ids=` on user update persists even when the user save then fails validation.
- Admins may set `admin` on any user (including demoting or promoting themselves).
- Field destroy keeps the physical `cf_*` column and its data.
- Settings store SMTP/IMAP passwords in plain YAML.

Not ported: `Admin::LeadsController#import` (multipart CSV, HTML redirect), plugins (read-only),
`confirm`/`auto_complete`/`options`/`redraw`/`subform` and `new`/`edit` (HTML/JS only).

Open questions: should Spring send Devise confirmation mail (needs a mailer decision); should the
Rails commit-then-500 responses be fixed on both stacks together; should field destroy ever
drop columns (needs a data-retention decision).
