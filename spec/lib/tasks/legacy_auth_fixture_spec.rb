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

  it "records the Rails-normalized login and account state the Spring tests rely on" do
    users = generated_users

    mixed = users.fetch("Legacy_MixedCase")
    expect(mixed.fetch("email")).to eq("legacy.mixed@example.com")
    expect(users.fetch("legacy_admin")).to include("admin" => true, "confirmed" => true, "suspended" => false)
    expect(users.fetch("legacy_suspended")).to include("admin" => false, "confirmed" => true, "suspended" => true)
    expect(users.fetch("legacy_unconfirmed")).to include("confirmed" => false, "suspended" => false)
    expect(users.except("legacy_admin").values).to all(include("admin" => false))
    expect(users.values).to all(satisfy { |attributes| attributes.fetch("email") == attributes.fetch("email").downcase })
  end

  it "writes authlogic_sha512 hashes, 20-char salts and the untrimmed passwords Spring must accept" do
    users = generated_users
    fixture = JSON.parse(File.read(output_path, encoding: "UTF-8"))

    expect(fixture.fetch("stretches")).to eq(User.stretches)
    expect(users.values.map { |attributes| attributes.fetch("encrypted_password") }).to all(match(/\A[0-9a-f]{128}\z/))
    expect(users.values.map { |attributes| attributes.fetch("password_salt") }).to all(match(/\A[A-Za-z0-9_-]{20}\z/))
    expect(users.values).to all(satisfy { |attributes| attributes.fetch("last_name") == attributes.fetch("username") })
    expect(users.fetch("legacy_dollar").fetch("password")).to include("$")
    expect(users.fetch("legacy_spaces").fetch("password")).to eq(" leading and trailing ")
  end

  def generated_users
    previous_output = ENV.fetch("OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    task = Rake::Task["ffcrm:migration:legacy_auth_fixture"]
    task.reenable
    task.invoke
    JSON.parse(File.read(output_path, encoding: "UTF-8")).fetch("users").index_by { |attributes| attributes.fetch("username") }
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
    Rake::Task["ffcrm:migration:legacy_auth_fixture"].reenable
  end
end
