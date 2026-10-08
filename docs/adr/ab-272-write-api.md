# ADR: Write API foundation + comments/emails/tasks/lists (AB-272 Phase A)

**Status**: proposed. ARB ticket: TO BE CREATED.

## Context

Epic AB-261 requires the Spring Boot service (`spring/`) to reach contract-diff
parity with the Rails monolith. Read families landed in AB-266/AB-270; Phase A of
AB-272 adds the first write paths, so a shared write foundation must exist that
every later family reuses without re-deciding statuses, validation rendering,
versioning or authz.

## Decision

- **Param envelope**: Rails wraps attributes in `{"<model>": {...}}` plus
  controller extras (`bucket`, `view`, `is_global`). DTOs keep that shape and
  `RailsParams` performs Rails-flavoured casts (`"abc"`→nil int, `"1"`→true,
  ISO→instant), so client payloads are identical against both stacks.
- **Statuses/bodies**: POST → 201 + body + `Location`; PUT/DELETE → 204; 422 body
  `{"errors": {attr: [full_message]}}`; RecordNotFound → 404 text/plain; CanCan
  denied → 401 (Spring 403, allow-listed).
- **Validation catalog**: generated JSON from the real Rails I18n
  (`rake ffcrm:migration:activemodel_messages` →
  `spring/src/main/resources/validation/activemodel_en_US.json`), CI drift check,
  ActiveModel lookup chain in `ActiveModelMessages`.
- **Versions**: `VersionRecorder` + `PaperTrailYaml` produce PaperTrail rows
  byte-identical to Psych output in the same transaction; timestamp-only updates
  write no version (PaperTrail notability). No Envers — columns/related/ignore
  must match PaperTrail exactly, which no generic audit library renders.
- **Authz**: reuse `hasPermission`/`CrmAccessPolicy`; one foundation extension —
  `RailsResources.savedList`. Rails security gaps are mirrored, not fixed.
- **Gateway**: append-only `__spring_w/<family>` write blocks, disabled by
  default, GET paths byte-identical.
- **Harness**: `reset: true` (TRUNCATE + snapshot replay) and `dbAssert`
  (post-response row diff incl. versions) in `contractTest`; `dualWriteSoak`
  Gradle task for concurrent cross-stack writes.

## Callback parity table

| Rails callback | File:line | Spring equivalent |
| --- | --- | --- |
| `Task#before_create :set_due_date` | app/models/task.rb | `TaskWriteService#applySetDueDate` |
| `Task#before_update :set_due_date, unless: :completed?` | app/models/task.rb | `TaskWriteService#applySetDueDate` |
| `Task validates :name, :user, :calendar` | app/models/task.rb | `TaskWriteService#validate` |
| `Task validate :specific_time` (Time.parse → 500 TypeError on nil calendar) | app/models/task.rb | `TaskWriteService#validate` → `RailsInternalError` |
| `TasksController#create/update/destroy/complete/uncomplete` via `Task.tracked_by` | app/controllers/tasks_controller.rb | `TasksWriteController` + `trackedTask` spec |
| `TaskObserver after_update complete/reassign/reschedule` | app/observers/task_observer.rb | `TaskWriteService#recordUpdateVersions` observer events |
| `CommentsController#create` `model.my(current_user).find_by_id` 404 | app/controllers/comments_controller.rb | `CommentWriteService#findCommentable` |
| `Comment#before_create :subscribe_mentioned_users`, `after_create :subscribe_user_to_entity` | app/models/comment.rb | `CommentWriteService#subscribeMentions`/`subscribe` |
| `ListsController#create` lower(name)+user_id upsert | app/controllers/lists_controller.rb | `ListWriteService#upsert` |
| `EmailsController#destroy` | app/controllers/emails_controller.rb | `EmailsController` + `EmailWriteService` |

## Consequences

Every subsequent write family (AB-272 phases B+) follows the "Adding a write
family" recipe in `spring/README.md` with no new foundation decisions. Mirrored
Rails gaps (list destroy without authz, task create without forced `user_id`,
comment param reassignment) are documented open questions, not fixed.
