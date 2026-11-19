# frozen_string_literal: true

require File.expand_path('../spec_helper', __dir__)

describe HomeController do
  render_views

  before do
    login
  end

  [false, true].each do |xhr|
    context "when requesting #{xhr ? 'the XHR JavaScript feed' : 'the HTML dashboard'}" do
      it "uses the updated user name on the next request" do
        author = create(:user, first_name: "Ada", last_name: "Lovelace")
        account = create(:account, user: current_user)
        create(:version, item: account, user: author, created_at: Time.current)

        get :index, xhr: xhr
        expect(response).to be_successful
        expect(response.body).to include("Ada Lovelace")

        author.update!(first_name: "Augusta")
        get :index, xhr: xhr

        expect(response).to be_successful
        expect(response.body).to include("Augusta Lovelace")
        expect(response.body).not_to include("Ada Lovelace")
      end

      it "excludes another user's private account from the activity feed" do
        owner = create(:user)
        hidden = create(:account, user: owner, access: "Private", name: "Confidential dashboard account")
        visible = create(:account, user: current_user, name: "Visible dashboard account")
        hidden_activity = create(:version, item: hidden, user: owner, created_at: Time.current)
        visible_activity = create(:version, item: visible, user: current_user, created_at: Time.current)

        get :index, xhr: xhr

        expect(response).to be_successful
        expect(assigns(:activities)).to include(visible_activity)
        expect(assigns(:activities)).not_to include(hidden_activity)
        expect(response.body).to include(visible.name)
        expect(response.body).not_to include(hidden.name)
      end
    end
  end
end
