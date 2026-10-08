# frozen_string_literal: true

require File.expand_path("../../../spec_helper", __dir__)
require "fat_free_crm/migration/i18n_properties"
require "tmpdir"

RSpec.describe FatFreeCRM::Migration::I18nProperties do
  let(:output_dir) { Rails.root.join("spring/src/main/resources/i18n").to_s }

  it "regenerates the committed properties files byte-for-byte" do
    Dir.mktmpdir do |tmp|
      skipped = described_class.generate(
        locales_dir: Rails.root.join("config/locales").to_s,
        output_dir: tmp
      )
      expect(skipped).not_to be_empty
      Dir.glob(File.join(tmp, "messages_*.properties")).each do |generated|
        committed = File.join(output_dir, File.basename(generated))
        expect(File.read(generated, encoding: "UTF-8")).to eq(File.read(committed, encoding: "UTF-8"))
      end
      expect(Dir.glob(File.join(tmp, "messages_*.properties")).size).to eq(18)
    end
  end

  it "produces ICU plural choice formats with =0 for zero" do
    en_us = File.read(File.join(output_dir, "messages_en_US.properties"), encoding: "UTF-8")
    expect(en_us).to include("{count, plural,")
    expect(en_us).to include("=0{")
  end

  it "keeps plain string leaves verbatim in Rails %<name>s-style form" do
    en_us = File.read(File.join(output_dir, "messages_en_US.properties"), encoding: "UTF-8")
    expect(en_us).to include("%{")
    expect(en_us).not_to match(/^[^=\n]*=\{count\}/)
  end

  it "converts %<count>s inside plural branches to a non-grouped number argument" do
    en_us = File.read(File.join(output_dir, "messages_en_US.properties"), encoding: "UTF-8")
    expect(en_us).to include("{count, number, ::group-off}")
  end

  it "escapes apostrophes, literal braces and hash inside plural branches" do
    escaped = described_class.icu_message("don't {x} #y", in_plural: true, context: "k")
    expect(escaped).to eq("don''t '{'x'}' '#'y")
  end

  it "raises on %% and %<x> interpolations" do
    expect do
      described_class.icu_message("50%% done", in_plural: false, context: "k")
    end.to raise_error(/%%/)
    expect do
      described_class.icu_message("%<name>s hi", in_plural: false, context: "k")
    end.to raise_error(/%<x>/)
  end

  it "raises when a plural hash lacks 'other'" do
    expect do
      described_class.icu_plural({ "one" => "one thing" }, "k")
    end.to raise_error(/other/)
  end

  it "escapes properties keys and leading-space values" do
    expect(described_class.escape_key("a b:c=d")).to eq('a\\ b\\:c\\=d')
    expect(described_class.escape_value(" lead")).to eq('\\ lead')
  end
end
