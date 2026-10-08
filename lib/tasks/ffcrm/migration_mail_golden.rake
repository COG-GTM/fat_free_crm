# frozen_string_literal: true

require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Generate Rails golden cases for Spring mail and IMAP behavior"
    task mail_golden: :environment do
      destination = Rails.root.join("spring/src/test/resources/mail")
      FileUtils.mkdir_p(destination)
      prior_queue_adapter = ActiveJob::Base.queue_adapter
      prior_host = Rails.application.routes.default_url_options[:host]
      prior_mailer_url_options = ActionMailer::Base.default_url_options.dup
      ActiveJob::Base.queue_adapter = :test

      begin
        ActiveRecord::Base.transaction(requires_new: true) do
          sort_hash = lambda do |value|
            case value
            when Hash
              value.keys.sort.index_with { |key| sort_hash.call(value[key]) }
            when Array
              value.map { |entry| sort_hash.call(entry) }
            else
              value
            end
          end
          write_golden = lambda do |path, value|
            File.write(path, JSON.pretty_generate(sort_hash.call(value)) + "\n")
          end
          mail_payload = lambda do |message|
            part = message.html_part || message.text_part || message
            {
              "from" => message.from || [],
              "raw_from" => message[:from]&.value,
              "to" => message.to || [],
              "raw_to" => message[:to]&.value,
              "subject" => message.subject,
              "content_type" => message.mime_type,
              "charset" => part.charset,
              "body" => part.decoded
            }
          end

          Rails.application.routes.default_url_options[:host] = "crm.example.test"
          ActionMailer::Base.default_url_options.merge!(host: "crm.example.test", protocol: "https")
          Setting.where(name: %w[smtp email_comment_replies]).delete_all
          Setting.email_dropbox = {
            address: "dropbox@example.test",
            address_aliases: ["alias@example.test"]
          }
          Setting.clear_cache!
          timestamp = Time.utc(2026, 3, 12, 2, 30, 0)
          assigner = User.new(
            id: 970_001, username: "golden-assigner", first_name: "Casey", last_name: "Sender",
            email: "casey@example.test", encrypted_password: "golden", password_salt: "golden",
            created_at: timestamp, updated_at: timestamp
          )
          assigner.save!(validate: false)
          recipient = User.new(
            id: 970_002, username: "golden-recipient", first_name: "Taylor", last_name: "Receiver",
            email: "taylor@example.test", encrypted_password: "golden", password_salt: "golden",
            created_at: timestamp, updated_at: timestamp
          )
          recipient.save!(validate: false)
          account = Account.new(
            id: 970_003, user: assigner, access: "Public", name: "Example Account",
            created_at: timestamp, updated_at: timestamp
          )
          account.assignee = recipient
          account.save!(validate: false)
          account.tag_list = %w[customer priority]
          account.save!(validate: false)
          contact = Contact.new(
            id: 970_005, user: assigner, access: "Public", first_name: "Ari", last_name: "Contact",
            created_at: timestamp, updated_at: timestamp
          )
          contact.assignee = recipient
          contact.save!(validate: false)
          lead = Lead.new(
            id: 970_006, user: assigner, access: "Public", first_name: "Lee", last_name: "Lead",
            status: "new", created_at: timestamp, updated_at: timestamp
          )
          lead.assignee = recipient
          lead.save!(validate: false)
          opportunity = Opportunity.new(
            id: 970_007, user: assigner, access: "Public", name: "Fixed Opportunity",
            stage: "prospecting", created_at: timestamp, updated_at: timestamp
          )
          opportunity.assignee = recipient
          opportunity.save!(validate: false)
          comment = Comment.new(
            id: 970_004, user: assigner, commentable: account,
            comment: "Golden <b>comment</b> & tea",
            created_at: timestamp, updated_at: timestamp
          )
          comment.save!(validate: false)
          dropbox_email = Struct.new(:subject, :body_plain).new("Golden message", "A deterministic body")

          mail_settings_input = lambda do
            %w[smtp email_comment_replies].filter_map do |name|
              setting = Setting.find_by_name(name)
              [name, setting.value] if setting
            end.to_h
          end
          assignment_input = lambda do |entity, route|
            {
              "to" => recipient.email,
              "entity_name" => entity.name,
              "entity_type" => entity.class.name,
              "entity_url" => "https://crm.example.test/#{route}/#{entity.id}",
              "assigner_name" => assigner.name,
              "settings" => mail_settings_input.call
            }
          end
          comment_input_for = lambda do |to_user, comment|
            entity = comment.commentable
            {
              "kind" => "comment",
              "to" => to_user.email,
              "from_user_name" => comment.user.full_name,
              "entity_name" => entity.respond_to?(:full_name) ? entity.full_name : entity.name,
              "entity_type" => entity.class.name,
              "entity_id" => entity.id,
              "tags" => entity.tags.join(", "),
              "comment" => comment.comment,
              "settings" => mail_settings_input.call
            }
          end
          mailers = {}
          no_settings_assignment = assignment_input.call(account, "accounts")
          mailers["assigned_account_no_mail_settings"] = {
            "input" => { "kind" => "assignment" }.merge(no_settings_assignment),
            "output" => mail_payload.call(UserMailer.assigned_entity_notification(account, assigner))
          }
          no_settings_comment_input = comment_input_for.call(recipient, comment)
          mailers["comment_notification_no_mail_settings"] = {
            "input" => no_settings_comment_input,
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, comment))
          }

          Setting.smtp = { from: "crm@example.test" }
          Setting.email_comment_replies = { address: "reply@example.test" }
          Setting.clear_cache!
          {
            "account" => [account, "accounts"],
            "contact" => [contact, "contacts"],
            "lead" => [lead, "leads"],
            "opportunity" => [opportunity, "opportunities"]
          }.each do |kind, (entity, route)|
            input = assignment_input.call(entity, route)
            mailers["assigned_#{kind}"] = {
              "input" => { "kind" => "assignment" }.merge(input),
              "output" => mail_payload.call(UserMailer.assigned_entity_notification(entity, assigner))
            }
          end
          special_account = Account.new(
            id: 970_009, user: assigner, access: "Public", name: %(R&D <"Special"> 'Account'),
            created_at: timestamp, updated_at: timestamp
          )
          special_account.assignee = recipient
          special_account.save!(validate: false)
          special_assignment_input = assignment_input.call(special_account, "accounts")
          mailers["assigned_account_special_entity_name"] = {
            "input" => { "kind" => "assignment" }.merge(special_assignment_input),
            "output" => mail_payload.call(UserMailer.assigned_entity_notification(special_account, assigner))
          }

          comment_input = comment_input_for.call(recipient, comment)
          mailers["comment_notification"] = {
            "input" => comment_input,
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, comment))
          }
          untagged_comment = Comment.new(
            id: 970_008, user: recipient, commentable: contact, comment: "Untagged reply",
            created_at: timestamp, updated_at: timestamp
          )
          untagged_comment.save!(validate: false)
          mailers["comment_notification_untagged"] = {
            "input" => comment_input_for.call(assigner, untagged_comment),
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(assigner, untagged_comment))
          }
          lead_comment = Comment.new(
            id: 970_010, user: assigner, commentable: lead, comment: "Lead full name",
            created_at: timestamp, updated_at: timestamp
          )
          lead_comment.save!(validate: false)
          mailers["comment_notification_lead_full_name"] = {
            "input" => comment_input_for.call(recipient, lead_comment),
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, lead_comment))
          }
          sanitized_comment = Comment.new(
            id: 970_011, user: assigner, commentable: account,
            comment: "<script>alert(1)</script>\nA newline &amp",
            created_at: timestamp, updated_at: timestamp
          )
          sanitized_comment.save!(validate: false)
          mailers["comment_notification_sanitized_script"] = {
            "input" => comment_input_for.call(recipient, sanitized_comment),
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, sanitized_comment))
          }
          Setting.email_comment_replies = { address: "" }
          Setting.smtp = { from: "fallback@example.test" }
          Setting.clear_cache!
          mailers["comment_notification_smtp_fallback"] = {
            "input" => comment_input_for.call(recipient, comment),
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, comment))
          }
          Setting.email_comment_replies = { address: "Support Team <named-reply@example.test>" }
          Setting.smtp = { from: "crm@example.test" }
          Setting.clear_cache!
          mailers["comment_notification_named_reply_address"] = {
            "input" => comment_input_for.call(recipient, comment),
            "output" => mail_payload.call(SubscriptionMailer.comment_notification(recipient, comment))
          }
          Setting.email_comment_replies = { address: "reply@example.test" }
          Setting.smtp = { from: "crm@example.test" }
          Setting.email_dropbox = {
            address: "dropbox@example.test",
            address_aliases: ["alias@example.test"]
          }
          Setting.clear_cache!

          mailers["dropbox_notification"] = {
            "input" => {
              "kind" => "dropbox",
              "to" => recipient.email,
              "from" => "casey@example.test",
              "subject" => dropbox_email.subject,
              "body" => dropbox_email.body_plain,
            "mediator_links" => ["Account ##{account.id}"]
            },
            "output" => mail_payload.call(
              DropboxMailer.dropbox_notification(
                recipient, "casey@example.test", dropbox_email, ["Account ##{account.id}"]
              )
            )
          }

          devise = mail_payload.call(DeviseMailer.reset_password_instructions(recipient, "golden-reset-token"))
          mailers["devise_reset_password"] = {
            "input" => { "kind" => "devise_reset", "to" => recipient.email, "token" => "golden-reset-token" },
            "output" => devise
          }
          mailers["devise_confirmation_instructions"] = {
            "input" => { "kind" => "devise_confirmation", "to" => recipient.email, "token" => "golden-token" },
            "output" => mail_payload.call(DeviseMailer.confirmation_instructions(recipient, "golden-token"))
          }
          mailers["devise_password_change"] = {
            "input" => { "kind" => "devise_password_change", "to" => recipient.email },
            "output" => mail_payload.call(DeviseMailer.password_change(recipient))
          }
          write_golden.call(destination.join("mailers.json"), mailers)

          require "mail"
          require "fat_free_crm/mail_processor/base"
          require "fat_free_crm/mail_processor/dropbox"
          require "fat_free_crm/mail_processor/comment_replies"
          processor = FatFreeCRM::MailProcessor::Base.new
          dropbox = FatFreeCRM::MailProcessor::Dropbox.new
          comment_replies = FatFreeCRM::MailProcessor::CommentReplies.new
          capture = lambda do |object, method, email|
            captured = nil
            object.send(method, email) { |*arguments| captured = arguments }
            captured
          end
          processor_cases = Dir.glob(destination.join("eml/*.eml")).to_h do |path|
            email = Mail.read(path)
            recipients = []
            dropbox.send(:with_recipients, email) do |recipient|
              recipients << recipient
              false
            end
            body = processor.send(:plain_text_body, email)
            [File.basename(path), {
              "is_valid" => processor.send(:is_valid?, email),
              "plain_text_body" => body,
              "parsed_reply" => EmailReplyParser.parse_reply(body),
              "subject_line" => capture.call(comment_replies, :with_subject_line, email),
              "explicit_keyword" => capture.call(dropbox, :with_explicit_keyword, email),
              "recipients" => recipients,
              "forwarded_recipient" => capture.call(dropbox, :with_forwarded_recipient, email),
              "from" => email.from || [],
              "to" => email.to_addrs || [],
              "cc" => email.cc_addrs || [],
              "message_id" => email.message_id,
              "subject" => email.subject,
              "date" => email.date&.iso8601
            }]
          end
          write_golden.call(destination.join("processor.json"), processor_cases)

          require "email_reply_parser"
          parser_fixture_dir = Gem::Specification.find_by_name("email_reply_parser_ffcrm").full_gem_path
          parser_cases = Dir.glob(File.join(parser_fixture_dir, "test/emails/*.txt")).to_h do |file|
            [File.basename(file), EmailReplyParser.parse_reply(File.read(file))]
          end
          write_golden.call(destination.join("email_reply_parser_golden.json"), parser_cases)

          mail_text_path = Rails.root.join("spring/src/main/resources/mail/mail_text_en_US.properties")
          mail_text_keys = []
          rails_literal = false
          File.foreach(mail_text_path, encoding: "UTF-8") do |line|
            if line.start_with?("# Rails literal ")
              rails_literal = true
            elsif line.match?(/\A[^#!\s][^=]*=/)
              mail_text_keys << line.split("=", 2).first unless rails_literal
              rails_literal = false
            elsif !line.start_with?("#") && !line.strip.empty?
              rails_literal = false
            end
          end
          mail_text = mail_text_keys.index_with do |key|
            I18n.t(key, locale: :"en-US")
          end
          write_golden.call(destination.join("mail_text.json"), mail_text)

          require "active_support/testing/time_helpers"
          time_helpers = Object.new.extend(ActiveSupport::Testing::TimeHelpers)
          capture_job = lambda do |enqueue|
            ActiveJob::Base.queue_adapter.enqueued_jobs.clear
            job = enqueue.call
            test_adapter_job = job.serialize.deep_dup
            test_adapter_job.delete("job_id")
            test_adapter_job.delete("enqueued_at")
            solid_queue_job = SolidQueue::Job.enqueue(job)
            solid_queue_arguments = solid_queue_job.arguments.deep_dup
            solid_queue_arguments.delete("job_id")
            solid_queue_arguments.delete("enqueued_at")
            { "test_adapter" => test_adapter_job, "solid_queue" => solid_queue_arguments }
          end
          active_job_arguments = time_helpers.travel_to(Time.utc(2026, 1, 1)) do
            {
              "user_assignment" => capture_job.call(
                -> { UserMailer.assigned_entity_notification(account, assigner).deliver_later }
              ),
              "subscription_comment" => capture_job.call(
                -> { SubscriptionMailer.comment_notification(recipient, comment).deliver_later }
              ),
              "devise_confirmation" => capture_job.call(
                -> { DeviseMailer.confirmation_instructions(recipient, "golden-token").deliver_later }
              ),
              "devise_reset_password" => capture_job.call(
                -> { DeviseMailer.reset_password_instructions(recipient, "golden-reset-token").deliver_later }
              ),
              "devise_password_change" => capture_job.call(
                -> { DeviseMailer.password_change(recipient).deliver_later }
              ),
              "account_website" => capture_job.call(-> { AccountWebsiteJob.perform_later(account) }),
              "wikidata" => capture_job.call(-> { WikidataJob.perform_later(account) })
            }
          end
          write_golden.call(destination.join("active_job_arguments.json"), active_job_arguments)
          raise ActiveRecord::Rollback
        end
      ensure
        Rails.application.routes.default_url_options[:host] = prior_host if defined?(prior_host)
        ActionMailer::Base.default_url_options = prior_mailer_url_options if defined?(prior_mailer_url_options)
        ActiveJob::Base.queue_adapter = prior_queue_adapter if prior_queue_adapter
        Setting.clear_cache!
      end
    end
  end
end
