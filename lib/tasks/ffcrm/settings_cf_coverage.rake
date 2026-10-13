# frozen_string_literal: true

require "fat_free_crm/migration/settings_cf_coverage"

namespace :ffcrm do
  namespace :migration do
    desc "Census of Setting/cf_ call sites classified against the Spring migration (CHECK=1 to verify)"
    # rubocop:disable Rails/RakeEnvironment -- intentionally pure Ruby, no Rails env
    task :settings_cf_coverage do
      root = File.expand_path("../../..", __dir__)
      coverage = FatFreeCRM::Migration::SettingsCfCoverage.new(
        root: root,
        rules_path: File.join(root, "docs/migration/settings-cf-coverage.rules.yml"),
        markdown_path: File.join(root, "docs/migration/settings-cf-coverage.md")
      )
      rows = coverage.scan

      if ENV["CHECK"] == "1"
        result = coverage.check(rows)
        result[:line_drift].each do |line|
          warn "warning: line-number drift at #{line.inspect} (not failing)"
        end
        failures = result[:unclassified].map do |row|
          "unclassified #{row.kind} site: #{row.file}:#{row.line} #{row.snippet.inspect}"
        end
        result[:added].each { |sig| failures << "new call site not in committed coverage md: #{sig.inspect}" }
        result[:removed].each { |sig| failures << "call site in committed coverage md no longer found: #{sig.inspect}" }
        if failures.empty?
          puts "settings_cf_coverage: #{rows.size} call sites, all classified and matching the committed md"
        else
          warn failures.join("\n")
          abort "settings_cf_coverage check failed with #{failures.size} issue(s); regenerate with " \
                "`#{FatFreeCRM::Migration::SettingsCfCoverage::GENERATE_CMD}` and update rules if needed"
        end
      else
        markdown = coverage.markdown(rows)
        File.write(coverage.instance_variable_get(:@markdown_path), markdown)
        puts "wrote #{coverage.instance_variable_get(:@markdown_path)} (#{rows.size} call sites)"
      end
    end
    # rubocop:enable Rails/RakeEnvironment
  end
end
