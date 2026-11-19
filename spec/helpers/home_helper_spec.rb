# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../spec_helper')

describe HomeHelper do
  # Delete this example and add some real ones or delete this file
  it "should be included in the object returned by #helper" do
    included_modules = (class << helper; self; end).send :included_modules
    expect(included_modules).to include(HomeHelper)
  end

  describe "activity feed memoization" do
    let(:user) { create(:user, first_name: "Ada", last_name: "Lovelace") }

    it "renders the same user link as link_to and builds it once per user" do
      expect(helper.activity_user_link(user)).to eq(helper.link_to(user.full_name, helper.user_path(user)))
      expect(helper).not_to receive(:user_path)
      helper.activity_user_link(user)
    end

    it "renders the same avatar link as avatar_for and builds it once per user" do
      expected = helper.link_to(helper.avatar_for(user, size: :thumb), helper.user_path(user))
      expect(helper.activity_avatar_link(user)).to eq(expected)
      expect(helper).not_to receive(:avatar_for)
      helper.activity_avatar_link(user)
    end

    it "returns the same translation as t, including the default for unknown keys" do
      expect(helper.activity_translation("action_create", default: 0)).to eq(helper.t("action_create", default: 0))
      expect(helper.activity_translation("action_unknown_event", default: 0)).to eq(0)
      expect(helper.activity_translation("subject_account")).to eq(helper.t("subject_account"))
    end

    it "keeps separate entries for different users" do
      other = create(:user, first_name: "Grace", last_name: "Hopper")
      expect(helper.activity_user_link(user)).to include("Ada Lovelace")
      expect(helper.activity_user_link(other)).to include("Grace Hopper")
    end
  end

  describe "activity translation cache keys" do
    it "keeps the same missing key with and without a default separate" do
      key = "action_missing_dashboard_event"
      expected = helper.t(key)

      expect(helper.activity_translation(key, default: 0)).to eq(0)
      expect(helper.activity_translation(key)).to eq(expected)
      expect(helper.activity_translation(key, default: 0)).to eq(0)
      expect(helper.activity_translation(key)).to eq(expected)
    end

    it "does not reuse a missing translation when a default is supplied later" do
      key = "action_another_missing_dashboard_event"

      expect(helper.activity_translation(key)).to eq(helper.t(key))
      expect(helper.activity_translation(key, default: 0)).to eq(0)
    end
  end
end
