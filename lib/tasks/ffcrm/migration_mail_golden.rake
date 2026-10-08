# frozen_string_literal: true

require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Generate Rails golden cases for Spring mail and IMAP behavior"
    task mail_golden: :environment do
      destination = Rails.root.join("spring/src/test/resources/mail")
      FileUtils.mkdir_p(destination)

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
        {
          "from" => message.from&.join(", "),
          "to" => message.to&.join(", "),
          "subject" => message.subject,
          "text" => message.text_part ? message.text_part.decoded : message.body.decoded,
          "html" => message.html_part&.decoded
        }
      end

      prior_host = Rails.application.routes.default_url_options[:host]
      Rails.application.routes.default_url_options[:host] = Setting.host.presence || "crm.example.test"
      assigner = User.new(id: 7, first_name: "Casey", last_name: "Sender", email: "casey@example.test")
      recipient = User.new(id: 8, first_name: "Taylor", last_name: "Receiver", email: "taylor@example.test")
      account = Account.new(id: 9, name: "Example Account")
      account.assignee = recipient
      comment = Comment.new(id: 10, user: assigner, commentable: account, comment: "Golden comment")
      dropbox_email = Struct.new(:subject, :body_plain).new("Golden message", "A deterministic body")

      mailers = {
        "assigned_entity_notification" => mail_payload.call(
          UserMailer.assigned_entity_notification(account, assigner)
        ),
        "comment_notification" => mail_payload.call(
          SubscriptionMailer.comment_notification(recipient, comment)
        ),
        "dropbox_notification" => mail_payload.call(
          DropboxMailer.dropbox_notification(recipient, "casey@example.test", dropbox_email, ["Account #9"])
        )
      }

      devise = mail_payload.call(DeviseMailer.reset_password_instructions(recipient, "golden-reset-token"))
      mailers["devise_reset_password"] = devise
      write_golden.call(destination.join("mailers.json"), mailers)

      require "mail"
      require "fat_free_crm/mail_processor/base"
      processor = FatFreeCRM::MailProcessor::Base.new
      plain = Mail.new("From: sender@example.test\nContent-Type: text/plain; charset=UTF-8\n\nTop reply\n")
      html = Mail.new("From: sender@example.test\nContent-Type: text/html; charset=UTF-8\n\n<p>Only HTML</p>\n")
      write_golden.call(destination.join("processor.json"), {
                          "plain_text_body" => processor.send(:plain_text_body, plain),
                          "html_message_is_valid" => processor.send(:is_valid?, html)
                        })

      require "email_reply_parser"
      parser_fixture_dir = Gem::Specification.find_by_name("email_reply_parser_ffcrm").full_gem_path
      parser_cases = Dir.glob(File.join(parser_fixture_dir, "test/emails/*.txt")).to_h do |file|
        [File.basename(file), EmailReplyParser.parse_reply(File.read(file))]
      end
      write_golden.call(destination.join("email_reply_parser_golden.json"), parser_cases)

      active_job_arguments = ActiveJob::Arguments.serialize(
        ["UserMailer", "assigned_entity_notification", "deliver_now", { "args" => [account, assigner] }]
      )
      write_golden.call(destination.join("active_job_arguments.json"), active_job_arguments)
    ensure
      Rails.application.routes.default_url_options[:host] = prior_host if defined?(prior_host)
    end
  end
end
