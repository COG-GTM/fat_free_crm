# AB-273: peripherals migration tracks

## Context

The Rails application owns asynchronous mail delivery, periodic background
jobs, account enrichment, and IMAP polling. Moving the API runtime to Spring
without an explicit owner boundary could send duplicate mail, ingest the same
message twice, or run two enrichment pipelines. Rails behavior was inventoried
in `app/models/observers/entity_observer.rb:524-549`,
`app/models/polymorphic/comment.rb:24-71`,
`app/models/entities/account.rb:86-87`,
`app/mailers/{user_mailer,subscription_mailer,dropbox_mailer,devise_mailer}.rb`,
`app/views/{user,subscription,dropbox}_mailer/`,
`lib/fat_free_crm/mail_processor/{base,dropbox,comment_replies}.rb`,
`app/jobs/account_website_job.rb`,
`app/services/wikidata_service.rb`, and `db/schema.rb` (Solid Queue tables).

## Decision

Add the Spring jobs/mail/IMAP track behind `FFCRM_JOBS_OWNER`, defaulting to
`rails`; only `spring` ownership starts Quartz, connects to IMAP, schedules
one-off work, or delivers mail. Quartz recurring jobs use RAMJobStore and
PostgreSQL session advisory locks (`classid=1179009869`, `objid=1` Dropbox,
`2` comment replies, `3` Solid Queue drain). The Solid Queue bridge claims
supported work from the existing Rails tables in bounded batches; unknown job
classes are left ready. One-off jobs contain primitive IDs or serialized mail
fields only. The website and Wikidata clients use JDK `HttpClient`, Jsoup, and
Jackson with no automatic redirects, bounded timeouts and response size, and
optional private-address blocking disabled by default.

Mail template rendering is isolated from the Spring MVC Thymeleaf view
configuration. Mail settings resolve database-backed Rails values before the
Spring YAML defaults. IMAP drops attachments, follows Rails message validity,
sender and archive/discard rules, and records comment create versions. The
reply parser ports `email_reply_parser_ffcrm` 0.5.0; its upstream MIT license
and fixture emails are shipped with the JUnit goldens. Rails generates the
sorted JSON parity fixtures through `rake ffcrm:migration:mail_golden`.

## Alternatives

- Keep Rails as the sole owner permanently: safest initial deployment, but
  leaves background work outside the Spring migration.
- Use a persistent Quartz store: increases schema and recovery coupling; the
  agreed design uses RAMJobStore and documents one-off restart loss.
- Share IMAP folders or rely only on scheduler timing: cannot guarantee
  single-owner processing; PostgreSQL session locks are used for recurring
  polls.

## Dependencies

Spring Boot Mail, Thymeleaf, Quartz, Jsoup 1.21.x, and test-only GreenMail
2.1.x; PostgreSQL is the shared lock and Solid Queue store. Java `HttpClient`
and Jackson are already available. Resolved dependency versions are recorded
with the implementation verification report.

## NFR and security

No new endpoint or database is introduced. Jobs use bounded HTTP request sizes
and timeouts, no redirects, and an opt-in private-address block. SMTP and IMAP
credentials remain in the existing Rails settings/configuration sources and
must be supplied through deployment secrets; never place credentials in
goldens or logs. The owner switch and advisory locks prevent concurrent
side-effects. One-off RAMJobStore work is intentionally at-most-process-life.

## Operations and rollback

1. Deploy with `FFCRM_JOBS_OWNER=rails`; confirm Spring has no Quartz polling
   and Rails remains the only producer/consumer.
2. Configure Spring SMTP/IMAP settings, schedules, and external service access.
3. Stop Rails job/IMAP workers, change the Spring deployment to
   `FFCRM_JOBS_OWNER=spring`, and verify scheduler logs and mail inbox health.
4. Roll back by setting `FFCRM_JOBS_OWNER=rails` and restarting Rails workers.
   The Spring owner gate then prevents scheduled side effects.
5. Check failed Solid Queue rows and rerun any one-off work lost across a
   Spring restart; recurring schedules are registered on startup.

The Rails 0.6.3 IMAP constructor call uses positional arguments unsupported by
the installed gem, and its shared permission check uses `Permission.exists`;
the Spring implementation uses the supported APIs rather than reproducing
those defects. The en-US Rails mail locale is
`config/locales/fat_free_crm.en-US.yml`; Devise strings come from the Devise
gem because no `config/locales/devise.en-US.yml` exists.

## Architecture review

ARB ticket: **ARB-xxxx (placeholder; assign before merge)**. Review is required
for adding Quartz/mail infrastructure, external dependencies, and IMAP/SMTP
integrations. This decision record is not approval to merge.

## Track: jobs-mail operations addendum

### Context and decision

Rails ownership remains the default. Spring can take ownership through
`FFCRM_JOBS_OWNER=spring` only after the Rails cron and Solid Queue workers are
stopped. The inventory is `UserMailer`
(`app/mailers/user_mailer.rb:9-23`, trigger `app/models/observers/entity_observer.rb:9-28`),
subscription mail (`app/mailers/subscription_mailer.rb:9-35`,
`app/models/polymorphic/comment.rb:34-71`), Dropbox rendering
(`app/mailers/dropbox_mailer.rb:9-19`), the website/Wikidata jobs and the three
IMAP processor files under `lib/fat_free_crm/mail_processor/`.

Quartz is used for scheduling rather than JobRunr to keep the already selected
Spring scheduler integration small. Quartz RAMJobStore is selected over JDBC
JobStore because this bridge's scheduled work is restart-loss tolerant and
should not introduce another durable scheduler schema. A single owner gate is
preferred to dual-running Rails and Spring, which could duplicate mail, IMAP
processing and enrichment. Spring drains supported Solid Queue rows rather
than leaving the Rails worker active in parallel; unsupported classes remain
ready for Rails.

Quartz, Spring Mail, Thymeleaf, Angus Jakarta Mail, Jsoup, GreenMail and the
vendored reply parser are the selected components. Gradle resolves Spring Boot
3.5.16, Thymeleaf 3.1.5.RELEASE, Angus Jakarta Mail 2.0.5, Jsoup 1.21.2 and
GreenMail 2.1.3; `email_reply_parser_ffcrm` is vendored at 0.5.0 (MIT).

Cutover: stop Rails Dropbox/comment-reply cron; stop Solid Queue workers and
dispatcher and verify no claimed executions remain; set `FFCRM_JOBS_OWNER=spring`;
restart Spring; verify scheduler startup, advisory-lock acquisition and
queue-drain logs. Rollback sets the owner to `rails`, restarts Spring with
scheduling disabled, drains pending RAMJobStore one-offs, restarts Rails queue
workers and re-enables cron.

Deviations: MailText is en-US only pending settings-i18n; Devise token
generation remains Rails-owned; update/touch versions await the audit track;
attachments are ignored; and SSRF private-address blocking is disabled by
default for parity. Dropbox notification rendering remains available, but its
Rails caller is dead and Spring does not send it. SMTP/IMAP credentials remain
in existing Rails settings and are never emitted in logs or goldens; response
bodies are bounded and redirects are not followed. ARB ticket: **TO BE CREATED**.

### Net::IMAP constructor clarification

The locally installed `net-imap` 0.6.3 source retains the deprecated
`Net::IMAP.new(host, port, usessl)` positional form and converts it to keyword
options. The focused call reached a connection attempt and failed only because
localhost:143 had no server; it did not reproduce the `ArgumentError` stated
in the original brief.
