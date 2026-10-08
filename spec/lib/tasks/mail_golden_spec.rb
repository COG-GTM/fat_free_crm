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
    ]
    parsed = expected.map { |name| JSON.parse(root.join(name).read) }
    expect(parsed.first(3)).to all(be_a(Hash))
    expect(parsed.last).to be_a(Array)
  end
end
