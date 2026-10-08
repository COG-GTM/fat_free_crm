# frozen_string_literal: true

# AB-272: exports the en-US ActiveModel validation message catalog the Spring write API loads
# (spring/src/main/resources/validation/activemodel_en_US.json). Keys are sorted for
# deterministic diffs; human attribute names are resolved through human_attribute_name for
# every mapped column so Java never has to humanize.
module FatFreeCRM
  module Migration
    module ActivemodelMessages
      MODELS = [Task, Comment, Email, List, Account, Campaign, Contact, Lead, Opportunity, User].freeze

      module_function

      def generate
        deep_sort(
          "errors" => {
            "format" => ::I18n.t("errors.format"),
            "messages" => deep_stringify(::I18n.t("errors.messages")),
            "attributes" => deep_stringify(::I18n.t("errors.attributes", default: {}))
          },
          "activerecord" => {
            "errors" => deep_stringify(::I18n.t("activerecord.errors")),
            "attributes" => attributes
          }
        )
      end

      def attributes
        MODELS.each_with_object({}) do |klass, result|
          result[klass.model_name.i18n_key.to_s] = klass.column_names.index_with do |column|
            klass.human_attribute_name(column)
          end
        end
      end

      def deep_stringify(value)
        case value
        when Hash
          value.each_with_object({}) { |(key, item), hash| hash[key.to_s] = deep_stringify(item) }
        when Array
          value.map { |item| deep_stringify(item) }
        else
          value
        end
      end

      def deep_sort(value)
        case value
        when Hash
          value.keys.sort.index_with { |key| deep_sort(value[key]) }
        when Array
          value.map { |item| deep_sort(item) }
        else
          value
        end
      end
    end
  end
end
