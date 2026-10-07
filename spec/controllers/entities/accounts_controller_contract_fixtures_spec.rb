# frozen_string_literal: true

require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')
require_relative "../../../db/contract_fixtures"

# Pins the Rails-side authorization semantics that the AB-266 contract cases and the
# `authz-denied-401-vs-403` allow-list entry depend on: a Private record owned by another user
# is denied with HTTP 401 (plain text) for JSON requests, while Public/Shared/assigned records
# and admin access succeed.
describe AccountsController, :truncate do
  before do
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  def show_json(id)
    request.env["HTTP_ACCEPT"] = "application/json"
    get :show, params: { id: id }, format: :json
  end

  def index_json
    request.env["HTTP_ACCEPT"] = "application/json"
    get :index, format: :json
  end

  context "when signed in as Bob (user 3)" do
    before { sign_in User.find(3) }

    it "is denied Alice's private account with a 401 whose body is the Rails warning text, not JSON" do
      show_json(102)

      expect(response).to have_http_status(:unauthorized)
      expect(response.body).to eq(I18n.t(:msg_not_authorized, default: "You are not authorized to take this action."))
      expect { response.parsed_body }.to raise_error(JSON::ParserError)
    end

    it "is denied the admin's private account" do
      show_json(107)

      expect(response).to have_http_status(:unauthorized)
    end

    it "can read the public, user-shared, group-shared, assigned and owned accounts" do
      [101, 103, 104, 105, 106].each do |id|
        show_json(id)
        expect(response.status).to eq(200), "expected 200 for account #{id}, got #{response.status}"
        expect(response.media_type).to eq("application/json")
        expect(response.parsed_body.fetch("id")).to eq(id)
      end
    end

    it "lists exactly the accounts Account.my(bob) returns" do
      index_json

      expect(response).to have_http_status(:ok)
      ids = response.parsed_body.map { |row| row.fetch("id") }
      expect(ids.sort).to eq([101, 103, 104, 105, 106])
    end
  end

  context "when signed in as Carol (user 5, no groups or permissions)" do
    before { sign_in User.find(5) }

    it "is denied every non-public account" do
      [102, 103, 104, 105, 106, 107].each do |id|
        show_json(id)
        expect(response.status).to eq(401), "expected 401 for account #{id}, got #{response.status}"
      end
    end

    it "sees only the public account" do
      show_json(101)
      expect(response).to have_http_status(:ok)

      index_json
      expect(response.parsed_body.map { |row| row.fetch("id") }).to eq([101])
    end
  end

  context "when signed in as the admin (user 1)" do
    before { sign_in User.find(1) }

    it "can read every fixture account including other users' private ones" do
      (101..107).each do |id|
        show_json(id)
        expect(response.status).to eq(200), "expected 200 for account #{id}, got #{response.status}"
      end
    end
  end

  context "when the suspended user Sam (user 4) signs in" do
    it "cannot authenticate" do
      sign_in User.find(4)
      show_json(101)

      expect(response).to have_http_status(:unauthorized)
    end
  end

  context "without a signed-in user" do
    it "is rejected with 401 before any authorization check" do
      show_json(101)

      expect(response).to have_http_status(:unauthorized)
    end
  end
end
