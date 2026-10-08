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
  authz. Tracked as open questions for the epic.
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
