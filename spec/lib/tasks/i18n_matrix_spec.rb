# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "digest"
require "json"
require "rake"

RSpec.describe Rake::Task do
  let(:task_name) { "ffcrm:migration:i18n_matrix" }
  let(:output_path) { Rails.root.join("tmp", "i18n-matrix-#{SecureRandom.hex(4)}.json") }
  let(:committed_path) { Rails.root.join("spring/src/test/resources/i18n/rails_i18n_matrix.json") }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?(task_name)
  end

  around do |example|
    previous_output = ENV.fetch("OUTPUT", nil)
    ENV["OUTPUT"] = output_path.to_s
    example.run
  ensure
    previous_output.nil? ? ENV.delete("OUTPUT") : ENV["OUTPUT"] = previous_output
  end

  after do
    FileUtils.rm_f(output_path)
  end

  it "records fallback chains, the load-path census and deterministic cases" do
    generate_matrix
    first = Digest::MD5.file(output_path).hexdigest
    generate_matrix
    expect(Digest::MD5.file(output_path).hexdigest).to eq(first)
    matrix = JSON.parse(File.read(output_path, encoding: "UTF-8"))
    expect(matrix.fetch("app_locales").size).to eq(18)
    expect(matrix.fetch("fallbacks").fetch("en-US")).to eq(%w[en-US en])
    expect(matrix.fetch("fallbacks").fetch("de-AT")).to eq(%w[de-AT de en])
    expect(matrix.fetch("load_path_census").fetch("app").fetch("locales")).to eq(18)
    expect(matrix.fetch("cases")).not_to be_empty
    locales = matrix.fetch("cases").pluck("locale").uniq
    expect(locales).to eq(matrix.fetch("app_locales"))
  end

  it "keeps the committed matrix JSON up to date" do
    generate_matrix
    expect(Digest::MD5.file(output_path)).to eq(Digest::MD5.file(committed_path))
  end

  # Generation runs in its own bundle process; comparisons use MD5 digests because
  # letting RSpec diff two ~1 MB JSON strings on failure exhausts memory and the box kills it.
  def generate_matrix
    ok = system(
      {
        "RAILS_ENV" => "test",
        "OUTPUT" => output_path.to_s,
        # Keep the parent binstub out of the child's binstub lookup.
        "BUNDLE_BIN_PATH" => nil
      },
      "bundle", "exec", "rake", task_name,
      chdir: Rails.root.to_s
    )
    raise "rake #{task_name} failed" unless ok
  end
end
