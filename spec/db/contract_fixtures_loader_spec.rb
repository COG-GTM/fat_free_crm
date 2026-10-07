# frozen_string_literal: true

require "spec_helper"
require_relative "../../db/contract_fixtures"

RSpec.describe ContractFixtures, ".load!", :truncate do
  def seed_contract_fixtures(reset: true)
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    if reset
      ENV["CONTRACT_FIXTURES_RESET"] = "1"
    else
      ENV.delete("CONTRACT_FIXTURES_RESET")
    end
    described_class.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  def utc_time(value)
    value.is_a?(String) ? Time.find_zone!("UTC").parse(value) : value
  end

  it "loads into an empty database without the reset flag" do
    seed_contract_fixtures(reset: false)

    expect(User.count).to eq(5)
    expect(Account.count).to eq(7)
  end

  it "refuses to overwrite an existing database unless CONTRACT_FIXTURES_RESET=1" do
    existing = create(:user)

    expect { seed_contract_fixtures(reset: false) }
      .to raise_error(SystemExit, /refuse to load because users already exist.*CONTRACT_FIXTURES_RESET=1/)

    expect(User.pluck(:id)).to eq([existing.id])
    expect(Account.count).to eq(0)
    expect(Setting.count).to eq(0)
  end

  it "replaces stray data and reproduces the same corpus when reset" do
    create(:user, username: "stray-user")
    Tag.create!(name: "stray-tag")
    seed_contract_fixtures

    Tag.create!(name: "another-stray")
    Account.find(101).update_columns(name: "Mutated")
    seed_contract_fixtures

    expect(User.pluck(:username).sort).to eq(%w[admin alice bob carol sam])
    expect(Tag.pluck(:name).sort).to eq(%w[contract-contact contract-public])
    expect(Account.find(101).name).to eq("Contract Account 101")
    expect(Account.count).to eq(7)
    expect(Permission.count).to eq(10)
    expect(Setting.where(name: "secret_token").count).to eq(1)
    expect(Setting.secret_token).to eq(described_class::SECRET_TOKEN)
    expect(User.find(2).valid_password?("contract-password")).to be(true)
  end

  it "rolls back the whole corpus when seeding fails part-way" do
    allow(ContractFixtures::FixtureSupport).to receive(:reset_sequences!).and_raise(RuntimeError, "sequence boom")

    expect { seed_contract_fixtures }.to raise_error(RuntimeError, "sequence boom")

    expect(User.count).to eq(0)
    expect(Group.count).to eq(0)
    expect(Setting.count).to eq(0)
    expect(Account.count).to eq(0)
    expect(Permission.count).to eq(0)
  end

  it "pins user, group and setting timestamps to deterministic offsets from BASE_TIME" do
    seed_contract_fixtures
    base = described_class::BASE_TIME

    User.find_each do |user|
      expect(user.created_at).to eq(base + user.id)
      expect(user.updated_at).to eq(base + user.id)
      expect(user.confirmed_at).to eq(base + user.id)
    end
    expect(User.find(4).suspended_at).to eq(base + 4)
    expect(Group.find(1).created_at).to eq(base + 20)
    expect(Group.find(2).created_at).to eq(base + 21)
    expect(Setting.find_by!(name: "secret_token").created_at).to eq(base + 30)
  end

  it "pins entity, relation and metadata timestamps to deterministic offsets from BASE_TIME" do
    seed_contract_fixtures
    base = described_class::BASE_TIME

    expect(Account.find(101).created_at).to eq(base + 201)
    expect(Contact.find(207).updated_at).to eq(base + 307)
    expect(Task.find(601).created_at).to eq(base + 1301)
    expect(Task.find(603).completed_at).to eq(base + 603)
    expect(AccountContact.first.created_at).to eq(base + 1000)
    expect(Comment.order(:id).map(&:created_at)).to eq([base + 1010, base + 1011])
    expect(Address.first.created_at).to eq(base + 1020)

    metadata = ActiveRecord::Base.connection.select_rows("SELECT created_at, updated_at FROM ar_internal_metadata")
    expect(metadata).not_to be_empty
    metadata.flatten.each { |value| expect(utc_time(value)).to eq(base) }
  end

  it "seeds the documented owner, assignee and access pattern for every entity type" do
    seed_contract_fixtures
    documented_pattern = [
      [2, nil, "Public"],
      [2, nil, "Private"],
      [2, nil, "Shared"],
      [2, nil, "Shared"],
      [2, 3, "Private"],
      [3, nil, "Private"],
      [1, nil, "Private"]
    ]

    { Account => 100, Contact => 200, Lead => 300, Opportunity => 400, Campaign => 500 }.each do |model, base|
      rows = model.order(:id).pluck(:id, :user_id, :assigned_to, :access)
      expect(rows.map(&:first)).to eq(((base + 1)..(base + 7)).to_a)
      expect(rows.map { |row| row.drop(1) }).to eq(documented_pattern)
      expect(Permission.where(asset_type: model.name, asset_id: base + 3).pluck(:user_id, :group_id)).to eq([[3, nil]])
      expect(Permission.where(asset_type: model.name, asset_id: base + 4).pluck(:user_id, :group_id)).to eq([[nil, 2]])
      expect(model.where(deleted_at: nil).count).to eq(7)
    end
  end

  it "seeds the documented group memberships, admin flag and task ownership" do
    seed_contract_fixtures

    expect(User.find(2).groups.pluck(:name)).to eq(["Sales"])
    expect(User.find(4).groups.pluck(:name)).to eq(["Sales"])
    expect(User.find(3).groups.pluck(:name)).to eq(["Support"])
    expect(User.find(1)).to be_admin
    expect(User.where(admin: true).pluck(:id)).to eq([1])
    expect(Task.order(:id).pluck(:id, :user_id, :assigned_to, :completed_by)).to eq(
      [[601, 2, nil, nil], [602, 2, 3, nil], [603, 5, nil, 3], [604, 2, nil, nil], [605, 1, nil, nil]]
    )
    expect(Task.find(604).asset).to eq(Account.find(101))
  end

  it "keeps the Rails fixture users aligned with the Java harness users.yml" do
    seed_contract_fixtures
    users_yml = YAML.safe_load_file(
      Rails.root.join("spring/src/contractTest/resources/contract/users.yml")
    ).fetch("users")

    expect(users_yml.keys).to match_array(%w[admin alice bob sam carol])
    users_yml.each do |key, attributes|
      user = User.find(attributes.fetch("id"))
      expect(user.username).to eq(attributes.fetch("username"))
      expect(user.email).to eq(attributes.fetch("email"))
      expect(user.admin).to be(attributes.fetch("admin", false))
      expect(user.suspended_at.present?).to be(attributes.fetch("suspended", false))
      expect(user.first_name).to eq(key == "admin" ? "Contract" : key.capitalize)
    end
    expect(User.where("id >= 6").count).to eq(0)
  end
end
