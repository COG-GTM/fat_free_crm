# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:output_path) { Rails.root.join("tmp", "legacy-auth-fixture-#{SecureRandom.hex(4)}.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(
      "ffcrm:migration:legacy_auth_fixture"
    )
  end

  after do
    FileUtils.rm_f(output_path)
  end

  it "writes Rails-authenticated users without leaving database rows behind" do
    original_count = User.count
    previous_output = ENV.fetch("OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    task = Rake::Task["ffcrm:migration:legacy_auth_fixture"]
    task.reenable
    task.invoke

    fixture = JSON.parse(File.read(output_path, encoding: "UTF-8"))
    expect(fixture).to include(
      "generated_by" => "ffcrm:migration:legacy_auth_fixture",
      "rails_env" => Rails.env.to_s,
      "encryptor" => "authlogic_sha512",
      "stretches" => 1
    )
    expect(fixture.fetch("users")).not_to be_empty
    fixture.fetch("users").each do |attributes|
      user = User.new(
        encrypted_password: attributes.fetch("encrypted_password"),
        password_salt: attributes.fetch("password_salt")
      )
      expect(user.valid_password?(attributes.fetch("password"))).to be(true)
      expect(user.valid_password?("#{attributes.fetch('password')}wrong")).to be(false)
    end
    expect(User.count).to eq(original_count)
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
    Rake::Task["ffcrm:migration:legacy_auth_fixture"].reenable
  end
end
