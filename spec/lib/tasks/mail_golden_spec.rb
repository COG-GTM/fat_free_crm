# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "rake"

RSpec.describe Rake::Task, type: :task do
  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:mail_golden")
  end

  it "writes stable mail, processor, parser, and Active Job fixtures" do
    Rake::Task["ffcrm:migration:mail_golden"].reenable
    expect { Rake::Task["ffcrm:migration:mail_golden"].invoke }.not_to raise_error

    root = Rails.root.join("spring/src/test/resources/mail")
    expected = %w[
      mailers.json
      processor.json
      email_reply_parser_golden.json
      active_job_arguments.json
      mail_text.json
    ]
    parsed = expected.map { |name| JSON.parse(root.join(name).read) }
    expect(parsed).to all(be_a(Hash))
    expect(Dir[root.join("eml/*.eml")]).not_to be_empty

    expect(parsed[0].keys).to include(*required_mailer_cases)
    i18n_keys, rails_literal_keys = mail_text_keys
    expect(parsed.last.keys).to match_array(i18n_keys)
    expect(parsed.last.keys & rails_literal_keys).to be_empty
  end

  def required_mailer_cases
    %w[
      assigned_account_no_mail_settings
      assigned_account_special_entity_name
      comment_notification_no_mail_settings
      comment_notification_smtp_fallback
      comment_notification_named_reply_address
      comment_notification_sanitized_script
      comment_notification_lead_full_name
    ]
  end

  def mail_text_keys
    i18n_keys = []
    rails_literal_keys = []
    rails_literal = false
    File.foreach(Rails.root.join("spring/src/main/resources/mail/mail_text_en_US.properties"), encoding: "UTF-8") do |line|
      if line.start_with?("# Rails literal ")
        rails_literal = true
      elsif line.match?(/\A[^#!\s][^=]*=/)
        key = line.split("=", 2).first
        (rails_literal ? rails_literal_keys : i18n_keys) << key
        rails_literal = false
      elsif !line.start_with?("#") && !line.strip.empty?
        rails_literal = false
      end
    end
    [i18n_keys, rails_literal_keys]
  end
end
