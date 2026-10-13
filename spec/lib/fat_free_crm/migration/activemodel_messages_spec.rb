# frozen_string_literal: true

require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/activemodel_messages'
require 'json'

# AB-272: the Spring write API renders ActiveModel validation messages from the exported catalog;
# these specs pin the catalog shape and guard the committed JSON against drift from en.yml.
describe FatFreeCRM::Migration::ActivemodelMessages do
  let(:catalog) { described_class.generate }

  it "covers every AB-272 write family and commentable model" do
    expect(described_class::MODELS).to eq([Task, Comment, Email, List, Account, Campaign, Contact, Lead,
                                           Opportunity, User])
  end

  it "exports the messages the write services look up by key" do
    expect(catalog.dig("errors", "messages", "blank")).to eq("can't be blank")
    expect(catalog.dig("errors", "messages", "required")).to eq("must exist")
    expect(catalog.dig("activerecord", "errors", "models", "task", "attributes", "name", "missing_task_name"))
      .to eq("^Please specify task name.")
    expect(catalog.dig("activerecord", "errors", "models", "task", "attributes", "calendar", "invalid_date"))
      .to eq("^Please specify valid date.")
  end

  it "exports human attribute names for every column of every model" do
    described_class::MODELS.each do |klass|
      names = catalog.dig("activerecord", "attributes", klass.model_name.i18n_key.to_s)
      expect(names.keys).to match_array(klass.column_names)
      expect(names["user_id"]).to eq(klass.human_attribute_name("user_id")) if klass.column_names.include?("user_id")
    end
    expect(catalog.dig("activerecord", "attributes", "comment", "comment")).to eq(Comment.human_attribute_name("comment"))
  end

  it "deep sorts hash keys and stringifies symbol keys" do
    sorted = described_class.deep_sort("b" => { "z" => 1, "a" => [{ "y" => 1, "x" => 2 }] }, "a" => 0)
    expect(sorted.keys).to eq(%w[a b])
    expect(sorted["b"].keys).to eq(%w[a z])
    expect(sorted["b"]["a"].first.keys).to eq(%w[x y])
    expect(described_class.deep_stringify(outer: { inner: [:sym, { k: 1 }] })).to eq("outer" => { "inner" => [:sym, { "k" => 1 }] })
    expect(JSON.generate(catalog)).to eq(JSON.generate(described_class.generate))
  end

  it "matches the committed catalog the Spring API loads" do
    committed = Rails.root.join("spring", "src", "main", "resources", "validation", "activemodel_en_US.json")
    expect(File.read(committed, encoding: "UTF-8")).to eq("#{JSON.pretty_generate(catalog)}\n")
  end
end
