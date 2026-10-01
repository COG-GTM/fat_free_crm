# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'fat_free_crm/migration/column_census'

namespace :ffcrm do
  namespace :migration do
    desc "Census of dynamic cf_* custom field columns (FORMAT=markdown|json OUTPUT=path COUNT_ROWS=true)"
    task column_census: :environment do
      format = (ENV['FORMAT'] || 'markdown').downcase
      count_rows = ENV.fetch('COUNT_ROWS', 'true') != 'false'
      census = FatFreeCRM::Migration::ColumnCensus.new(count_rows: count_rows)

      output = case format
               when 'markdown', 'md' then census.to_markdown
               when 'json' then census.to_json
               else abort("Unknown FORMAT #{format.inspect}: expected markdown or json")
               end

      if ENV['OUTPUT'].present?
        File.write(ENV['OUTPUT'], output)
        puts "Wrote #{format} census to #{ENV['OUTPUT']}"
      else
        puts output
      end
    end

    desc "Schema-only dump of the live database for Flyway baselining (OUTPUT=path)"
    task baseline_dump: :environment do
      config = ActiveRecord::Base.connection_db_config
      output = ENV['OUTPUT'].presence || Rails.root.join("tmp/schema-baseline-#{Time.now.utc.strftime('%Y%m%dT%H%M%SZ')}.sql").to_s

      case config.adapter
      when 'postgresql'
        env = {}
        env['PGPASSWORD'] = config.configuration_hash[:password].to_s if config.configuration_hash[:password]
        args = ['pg_dump', '--schema-only', '--no-owner', '--no-privileges', '--file', output]
        args += ['--host', config.configuration_hash[:host].to_s] if config.configuration_hash[:host]
        args += ['--port', config.configuration_hash[:port].to_s] if config.configuration_hash[:port]
        args += ['--username', config.configuration_hash[:username].to_s] if config.configuration_hash[:username]
        args << config.database
        abort("pg_dump failed") unless system(env, *args)
      else
        ActiveRecord::Tasks::DatabaseTasks.structure_dump(config, output)
      end

      puts "Wrote #{config.adapter} schema dump to #{output}"
    end
  end
end
