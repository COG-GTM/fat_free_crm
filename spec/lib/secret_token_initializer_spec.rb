# frozen_string_literal: true

# Copyright (c) 2008-2014 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'
require 'fat_free_crm/secret_token_generator'

describe FatFreeCRM::SecretTokenGenerator, ".setup!" do
  let(:initializer) { Rails.root.join("config/initializers/secret_token.rb").to_s }

  before do
    allow(ENV).to receive(:[]).and_call_original
    allow(ENV).to receive(:[]).with("FFCRM_ENTITY_FIXTURE").and_return(flag)
    allow(Setting).to receive(:yaml_settings).and_return(Setting.yaml_settings.except(:secret_token))
    Setting.where(name: "secret_token").delete_all
    Setting.clear_cache!
  end

  after { Setting.clear_cache! }

  around do |example|
    original_secret_key_base = FatFreeCRM::Application.config.secret_key_base
    example.run
  ensure
    FatFreeCRM::Application.config.secret_key_base = original_secret_key_base
  end

  context "when FFCRM_ENTITY_FIXTURE is not set" do
    let(:flag) { nil }

    it "generates and persists the secret token after initialization" do
      expect(FatFreeCRM::SecretTokenGenerator).to receive(:setup!).once.and_call_original
      expect(Setting.secret_token).to be_blank

      load initializer

      expect(Setting.secret_token).to match(/\A\h{128}\z/)
      expect(Setting.where(name: "secret_token").count).to eq(1)
      expect(FatFreeCRM::Application.config.secret_key_base).to eq(Setting.secret_token)
    end
  end

  context "when FFCRM_ENTITY_FIXTURE has a value other than 1" do
    let(:flag) { "0" }

    it "still sets up the secret token" do
      expect(FatFreeCRM::SecretTokenGenerator).to receive(:setup!).once.and_call_original

      load initializer

      expect(Setting.secret_token).to match(/\A\h{128}\z/)
      expect(FatFreeCRM::Application.config.secret_key_base).to eq(Setting.secret_token)
    end
  end

  context "when FFCRM_ENTITY_FIXTURE=1" do
    let(:flag) { "1" }

    it "skips secret token setup so the settings table stays empty for fixture generation" do
      expect(FatFreeCRM::SecretTokenGenerator).not_to receive(:setup!)
      expect(Setting).not_to receive(:secret_token=)
      load initializer
      expect(Setting.count).to eq(0)
    end
  end

  context "when running as an engine" do
    let(:flag) { nil }

    it "leaves secret token setup to the host application" do
      allow(FatFreeCRM).to receive(:application?).and_return(false)
      expect(FatFreeCRM::SecretTokenGenerator).not_to receive(:setup!)
      load initializer
    end
  end
end
