# frozen_string_literal: true

require "spec_helper"
require_relative "../../../db/contract_fixtures"

# Pins the Rails-side JSON contract that the Spring Boot contract-diff harness
# (spring/src/contractTest) replays against the deterministic corpus seeded by
# db/contract_fixtures.rb: which fixture user can read which fixture record,
# and what Rails answers when access is denied.
describe AccountsController, :truncate do
  before do
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  def sign_in_fixture(id)
    sign_in(User.find(id))
  end

  def show_json(id)
    get :show, params: { id: id }, format: :json
    response
  end

  describe "unauthenticated access" do
    it "answers 401 JSON for the collection and a record" do
      get :index, format: :json
      expect(response).to have_http_status(:unauthorized)
      expect(response.media_type).to eq("application/json")

      show_json(101)
      expect(response).to have_http_status(:unauthorized)
    end
  end

  describe "alice (owner, id 2)" do
    before { sign_in_fixture(2) }

    it "sees every account she owns regardless of access level, but not bob's or admin's" do
      [101, 102, 103, 104, 105].each do |id|
        expect(show_json(id).code).to eq("200"), "account #{id}"
        expect(response.parsed_body["id"]).to eq(id)
      end
      [106, 107].each do |id|
        expect(show_json(id).code).to eq("401"), "account #{id}"
      end
    end

    it "lists exactly the five visible accounts in JSON" do
      get :index, format: :json
      expect(response).to have_http_status(:ok)
      expect(response.parsed_body.pluck("id")).to contain_exactly(101, 102, 103, 104, 105)
    end
  end

  describe "bob (assignee / shared / group member, id 3)" do
    before { sign_in_fixture(3) }

    it "sees public, user-shared, group-shared, assigned and own records only" do
      [101, 103, 104, 105, 106].each do |id|
        expect(show_json(id).code).to eq("200"), "account #{id}"
      end
      [102, 107].each do |id|
        expect(show_json(id).code).to eq("401"), "account #{id}"
      end
    end

    it "lists exactly the five visible accounts in JSON" do
      get :index, format: :json
      expect(response.parsed_body.pluck("id")).to contain_exactly(101, 103, 104, 105, 106)
    end
  end

  describe "carol (no relation, id 5)" do
    before { sign_in_fixture(5) }

    it "only sees the public account" do
      expect(show_json(101).code).to eq("200")
      (102..107).each do |id|
        expect(show_json(id).code).to eq("401"), "account #{id}"
      end
    end

    it "lists only the public account in JSON" do
      get :index, format: :json
      expect(response.parsed_body.pluck("id")).to eq([101])
    end
  end

  describe "admin (id 1)" do
    before { sign_in_fixture(1) }

    it "sees every account" do
      (101..107).each do |id|
        expect(show_json(id).code).to eq("200"), "account #{id}"
      end
      get :index, format: :json
      expect(response.parsed_body.pluck("id")).to match_array(101..107)
    end
  end

  describe "access-denied semantics" do
    before { sign_in_fixture(5) }

    # Rails answers a denied JSON request with HTTP 401 (not 403) and a plain
    # text warning that is *not* valid JSON, even though the negotiated
    # content type stays application/json. The harness' error-body allow-list
    # rules exist for exactly this shape.
    it "renders the CanCan warning as a non-JSON body with HTTP 401 rather than 403 or 404" do
      show_json(102)
      expect(response).to have_http_status(:unauthorized)
      expect(response.media_type).to eq("application/json")
      expect(response.body).to eq(I18n.t(:msg_not_authorized, default: "You are not authorized to take this action."))
      expect { response.parsed_body }.to raise_error(JSON::ParserError)
    end

    it "distinguishes a missing record (404) from a forbidden one (401)" do
      show_json(999)
      expect(response).to have_http_status(:not_found)
      expect(response.body).to eq(I18n.t(:msg_asset_not_available, value: "account"))
      expect { response.parsed_body }.to raise_error(JSON::ParserError)
    end
  end

  describe "serialized shape" do
    before { sign_in_fixture(2) }

    it "exposes the deterministic timestamps and the counter caches the Spring side must reproduce" do
      body = JSON.parse(show_json(101).body)
      expect(body.slice("id", "name", "user_id", "assigned_to", "access", "contacts_count", "opportunities_count",
                        "category", "rating", "deleted_at")).to eq(
                          "id" => 101, "name" => "Contract Account 101", "user_id" => 2, "assigned_to" => nil,
                          "access" => "Public", "contacts_count" => 1, "opportunities_count" => 1,
                          "category" => nil, "rating" => 0, "deleted_at" => nil
                        )
      expect(Time.iso8601(body["created_at"])).to eq(ContractFixtures::BASE_TIME + 201)
      expect(Time.iso8601(body["updated_at"])).to eq(ContractFixtures::BASE_TIME + 201)
    end
  end
end
