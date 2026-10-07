# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "json"
require "rake"

# Pins the shape of the fixture ffcrm:migration:legacy_auth_fixture writes, so the Spring
# authentication tests that consume it (AB-264) cannot drift from what Rails actually produces.
RSpec.describe "ffcrm:migration:legacy_auth_fixture scenarios" do # rubocop:disable RSpec/DescribeClass
  let(:output_path) { Rails.root.join("tmp", "legacy-auth-scenarios-#{SecureRandom.hex(4)}.json") }
  let(:task_name) { "ffcrm:migration:legacy_auth_fixture" }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(task_name)
  end

  after do
    FileUtils.rm_f(output_path)
    Rake::Task[task_name].reenable
  end

  def run_task(env = {})
    original_env = env.keys.index_with { |key| ENV.fetch(key, nil) }
    env.each { |key, value| ENV[key] = value }
    Rake::Task[task_name].reenable
    Rake::Task[task_name].invoke
  ensure
    original_env.each { |key, value| value.nil? ? ENV.delete(key) : ENV[key] = value }
  end

  def generated_users
    JSON.parse(File.read(output_path, encoding: "UTF-8")).fetch("users").index_by { |u| u.fetch("username") }
  end

  it "prints the output path, encryptor and stretches it used" do
    expect { run_task("OUTPUT" => output_path.to_s) }
      .to output(/Wrote #{Regexp.escape(output_path.to_s)}.*encryptor=authlogic_sha512 stretches=1/m).to_stdout
  end

  it "creates the output directory when it does not exist yet" do
    nested = Rails.root.join("tmp", "legacy-auth-#{SecureRandom.hex(4)}", "nested", "users.json")
    begin
      expect { run_task("OUTPUT" => nested.to_s) }.to output.to_stdout
      expect(File).to exist(nested)
    ensure
      FileUtils.rm_rf(nested.dirname.dirname)
    end
  end

  it "records the suspended, unconfirmed and admin flags exactly as Rails persisted them" do
    expect { run_task("OUTPUT" => output_path.to_s) }.to output.to_stdout
    users = generated_users

    expect(users.fetch("legacy_suspended")).to include("suspended" => true, "confirmed" => true, "admin" => false)
    expect(users.fetch("legacy_unconfirmed")).to include("confirmed" => false, "suspended" => false)
    expect(users.fetch("legacy_admin")).to include("admin" => true, "confirmed" => true, "suspended" => false)
    expect(users.fetch("legacy_plain")).to include("admin" => false, "confirmed" => true, "suspended" => false)
  end

  it "keeps mixed-case usernames but writes the Devise-downcased email" do
    expect { run_task("OUTPUT" => output_path.to_s) }.to output.to_stdout
    mixed = generated_users.fetch("Legacy_MixedCase")

    expect(mixed.fetch("email")).to eq("legacy.mixed@example.com")
    expect(mixed.fetch("last_name")).to eq("Legacy_MixedCase")
    expect(mixed.fetch("first_name")).to eq("Legacy")
  end

  it "preserves awkward passwords verbatim and gives every user a distinct salt" do
    expect { run_task("OUTPUT" => output_path.to_s) }.to output.to_stdout
    users = generated_users

    expect(users.fetch("legacy_spaces").fetch("password")).to eq(" leading and trailing ")
    expect(users.fetch("legacy_dollar").fetch("password")).to eq("pa$$w0rd$")
    expect(users.fetch("legacy_unicode").fetch("password")).to eq("pässwörd-日本語-🔐")
    expect(users.fetch("legacy_long").fetch("password")).to eq("long-" * 45)
    expect(users.values.map { |u| u.fetch("password_salt") }.uniq.size).to eq(users.size)
    users.each_value do |user|
      expect(user.fetch("encrypted_password")).to match(/\A[0-9a-f]{128}\z/)
    end
  end

  it "rolls back even when a scenario fails to save" do
    original_count = User.count
    allow_any_instance_of(User).to receive(:save!).and_raise(ActiveRecord::RecordInvalid) # rubocop:disable RSpec/AnyInstance

    expect { run_task("OUTPUT" => output_path.to_s) }.to raise_error(ActiveRecord::RecordInvalid)
    expect(User.count).to eq(original_count)
    expect(File).not_to exist(output_path)
  end
end
