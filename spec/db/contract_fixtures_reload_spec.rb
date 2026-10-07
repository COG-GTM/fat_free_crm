# frozen_string_literal: true

require "spec_helper"
require "yaml"
require_relative "../../db/contract_fixtures"

RSpec.describe ContractFixtures, :truncate do
  let(:users_yml) do
    YAML.safe_load_file(
      Rails.root.join("spring/src/contractTest/resources/contract/users.yml")
    ).fetch("users")
  end

  def with_reset_flag(value)
    previous = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = value
    yield
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous
  end

  describe ".load!" do
    it "seeds an empty database without the reset flag" do
      with_reset_flag(nil) { described_class.load! }

      expect(User.count).to eq(5)
      expect(Account.count).to eq(7)
    end

    it "refuses to overwrite existing users unless CONTRACT_FIXTURES_RESET=1" do
      existing = create(:user)

      with_reset_flag(nil) do
        expect { described_class.load! }
          .to raise_error(SystemExit)
          .and output(/refuse to load because users already exist/).to_stderr_from_any_process
      end

      expect(User.pluck(:id)).to eq([existing.id])
      expect(Account.count).to eq(0)
      expect(Setting.where(name: "secret_token")).not_to exist
    end

    it "reloads idempotently with the reset flag and leaves the id sequences past the fixture ids" do
      with_reset_flag("1") do
        described_class.load!
        described_class.load!
      end

      expect(User.pluck(:id).sort).to eq((1..5).to_a)
      expect(Account.pluck(:id).sort).to eq((101..107).to_a)
      expect(Task.pluck(:id).sort).to eq((601..605).to_a)
      expect(Permission.count).to eq(10)
      expect(Tagging.count).to eq(2)
      expect(Setting.where(name: "secret_token").count).to eq(1)

      account = Account.create!(name: "Created after fixtures", user: User.find(2), access: "Private")
      expect(account.id).to eq(108)
      expect(Tag.create!(name: "created-after-fixtures").id).to eq(3)
    end

    it "replaces a pre-existing secret_token setting instead of inserting a duplicate" do
      Setting.create!(name: "secret_token", value: "stale-token")

      with_reset_flag(nil) { described_class.load! }

      expect(Setting.where(name: "secret_token").count).to eq(1)
      expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
      setting = Setting.find_by(name: "secret_token")
      expect(setting.created_at).to eq(ContractFixtures::BASE_TIME + 30)
      expect(setting.updated_at).to eq(ContractFixtures::BASE_TIME + 30)
    end
  end

  describe "seeded rows" do
    before do
      with_reset_flag("1") { described_class.load! }
    end

    it "mirrors the identity, role and suspension flags from users.yml" do
      users_yml.each do |key, attributes|
        user = User.find(attributes.fetch("id"))
        expect(user.username).to eq(attributes.fetch("username"))
        expect(user.email).to eq(attributes.fetch("email"))
        expect(user.admin).to eq(attributes.fetch("admin", false))
        expect(user.suspended?).to eq(attributes.fetch("suspended", false))
        expect(user.first_name).to eq(key == "admin" ? "Contract" : key.capitalize)
        expect(user.last_name).to eq("Fixture")
      end

      expect(User.where(admin: true).pluck(:id)).to eq([1])
      expect(User.where.not(suspended_at: nil).pluck(:id)).to eq([4])
    end

    it "uses the deterministic clock and per-user salts" do
      User.find_each do |user|
        expect(user.password_salt).to eq("ab266-contract-salt-#{user.id}")
        expect(user.sign_in_count).to eq(0)
        expect(user.created_at).to eq(ContractFixtures::BASE_TIME + user.id)
        expect(user.updated_at).to eq(ContractFixtures::BASE_TIME + user.id)
        expect(user.confirmed_at).to eq(ContractFixtures::BASE_TIME + user.id)
      end

      expect(User.find(4).suspended_at).to eq(ContractFixtures::BASE_TIME + 4)
    end

    it "places alice and sam in Sales, bob in Support and leaves admin and carol ungrouped" do
      expect(Group.find(1).name).to eq("Sales")
      expect(Group.find(1).users.pluck(:id).sort).to eq([2, 4])
      expect(Group.find(2).name).to eq("Support")
      expect(Group.find(2).users.pluck(:id)).to eq([3])
      expect(User.find(1).groups).to be_empty
      expect(User.find(5).groups).to be_empty
    end

    it "applies the same ownership, assignment and access pattern to every entity type" do
      { Account => 100, Contact => 200, Lead => 300, Opportunity => 400, Campaign => 500 }.each do |model, base|
        expect(model.order(:id).pluck(:id, :user_id, :assigned_to, :access)).to eq([
                                                                                     [base + 1, 2, nil, "Public"],
                                                                                     [base + 2, 2, nil, "Private"],
                                                                                     [base + 3, 2, nil, "Shared"],
                                                                                     [base + 4, 2, nil, "Shared"],
                                                                                     [base + 5, 2, 3, "Private"],
                                                                                     [base + 6, 3, nil, "Private"],
                                                                                     [base + 7, 1, nil, "Private"]
                                                                                   ])
        expect(Permission.where(asset_type: model.name, asset_id: base + 3).pluck(:user_id, :group_id)).to eq([[3, nil]])
        expect(Permission.where(asset_type: model.name, asset_id: base + 4).pluck(:user_id, :group_id)).to eq([[nil, 2]])
        expect(Permission.where(asset_type: model.name).where.not(asset_id: [base + 3, base + 4])).not_to exist
        expect(model.find(base + 1).created_at).to eq(ContractFixtures::BASE_TIME + base + 101)
        expect(model.where(deleted_at: nil).count).to eq(7)
      end
    end

    it "seeds the task corpus with owners, assignees, completion and asset links" do
      expect(Task.order(:id).pluck(:id, :user_id, :assigned_to, :completed_by, :asset_type, :asset_id)).to eq([
                                                                                                                [601, 2, nil, nil, nil, nil],
                                                                                                                [602, 2, 3, nil, nil, nil],
                                                                                                                [603, 5, nil, 3, nil, nil],
                                                                                                                [604, 2, nil, nil, "Account", 101],
                                                                                                                [605, 1, nil, nil, nil, nil]
                                                                                                              ])
      expect(Task.find(603).completed_at).to eq(ContractFixtures::BASE_TIME + 603)
      expect(Task.where(completed_at: nil).pluck(:id).sort).to eq([601, 602, 604, 605])
      expect(Task.find(604).asset).to eq(Account.find(101))
    end

    it "links the public account to its contact, opportunity, lead chain and campaign" do
      expect(Account.find(101).contacts.pluck(:id)).to eq([201])
      expect(Account.find(101).opportunities.pluck(:id)).to eq([401])
      expect(Contact.find(201).opportunities.pluck(:id)).to eq([401])
      expect(Contact.find(201).lead).to eq(Lead.find(301))
      expect(Lead.find(301).campaign).to eq(Campaign.find(501))
      expect(Account.find(101).contacts_count).to eq(1)
      expect(Account.find(101).opportunities_count).to eq(1)
      expect(Campaign.find(501).leads_count).to eq(1)
      expect(Account.find(101).taggings.joins(:tag).pluck("tags.name", :tagger_id)).to eq([["contract-public", 2]])
      expect(Contact.find(201).taggings.joins(:tag).pluck("tags.name", :tagger_id)).to eq([["contract-contact", 3]])
      expect(Tag.order(:id).pluck(:name, :taggings_count)).to eq([["contract-public", 1], ["contract-contact", 1]])
    end

    it "stamps ar_internal_metadata and the secret token with the fixture clock" do
      metadata = ActiveRecord::Base.connection.select_values("SELECT updated_at FROM ar_internal_metadata")
      expect(metadata).not_to be_empty
      metadata.each { |value| expect(value.to_s).to start_with("2026-01-01 09:00:00") }

      setting = Setting.find_by(name: "secret_token")
      expect(setting.created_at).to eq(ContractFixtures::BASE_TIME + 30)
      expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
    end
  end
end
