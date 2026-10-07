# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/authz_matrix'
require 'fat_free_crm/migration/authz_contract_corpus'

describe FatFreeCRM::Migration::AuthzContractCorpus do
  it "is seeded inside a rollback and therefore refuses CONTRACT_FIXTURES_RESET" do
    previous = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    connection = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: "PostgreSQL")
    allow(Setting).to receive(:clear_cache!)

    expect { described_class.new(connection).generate }
      .to raise_error(RuntimeError, /unset CONTRACT_FIXTURES_RESET/)
    expect(Setting).to have_received(:clear_cache!)
  ensure
    previous.nil? ? ENV.delete("CONTRACT_FIXTURES_RESET") : ENV["CONTRACT_FIXTURES_RESET"] = previous
  end

  it "records the five contract-corpus users and adds the corpus' extra sequences" do
    expect(described_class::USERS).to eq(%w[admin alice bob sam carol])
    expect(described_class::EXTRA_SEQUENCES).to include("settings", "addresses", "tags", "taggings")
    expect(described_class.superclass).to eq(FatFreeCRM::Migration::AuthzMatrix)
  end
end
