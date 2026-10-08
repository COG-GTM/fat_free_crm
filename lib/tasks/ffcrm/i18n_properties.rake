# frozen_string_literal: true

require "fileutils"
require "fat_free_crm/migration/i18n_properties"

REPO_ROOT = File.expand_path("../../..", __dir__)

namespace :ffcrm do
  namespace :migration do
    desc "Generate Spring ICU .properties files from config/locales (no Rails env)"
    # rubocop:disable Rails/RakeEnvironment -- intentionally pure Ruby, no Rails env
    task :i18n_properties do
      locales_dir = ENV.fetch("LOCALES_DIR", File.join(REPO_ROOT, "config/locales"))
      output_dir = ENV.fetch("OUTPUT_DIR", File.join(REPO_ROOT, "spring/src/main/resources/i18n"))

      skipped = FatFreeCRM::Migration::I18nProperties.generate(locales_dir: locales_dir, output_dir: output_dir)
      puts "wrote .properties files to #{output_dir}"
      unless skipped.empty?
        puts "skipped #{skipped.size} entr#{skipped.size == 1 ? 'y' : 'ies'}:"
        skipped.each { |entry| puts "  #{entry}" }
      end
    end
    # rubocop:enable Rails/RakeEnvironment
  end
end
