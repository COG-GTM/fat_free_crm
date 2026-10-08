# frozen_string_literal: true

require "fileutils"
require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Export the en-US ActiveModel validation message catalog for the Spring write API " \
         "(OUTPUT, default spring/src/main/resources/validation/activemodel_en_US.json)"
    task activemodel_messages: :environment do
      require "fat_free_crm/migration/activemodel_messages"
      output = Rails.root.join(
        ENV.fetch("OUTPUT", "spring/src/main/resources/validation/activemodel_en_US.json")
      )
      FileUtils.mkdir_p(output.dirname)
      File.write(output, "#{JSON.pretty_generate(FatFreeCRM::Migration::ActivemodelMessages.generate)}\n",
                 encoding: "UTF-8")
      puts "Wrote #{output}"
    end
  end
end
