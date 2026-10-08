# frozen_string_literal: true

require File.expand_path(File.dirname(__FILE__) + '/../../../spec_helper')
require 'fat_free_crm/migration/activemodel_messages'

describe FatFreeCRM::Migration::ActivemodelMessages do
  describe '.deep_sort' do
    it 'sorts hash keys recursively, including inside arrays, and leaves scalars alone' do
      sorted = described_class.deep_sort('b' => { 'z' => 1, 'a' => [{ 'y' => 2, 'x' => 3 }, 'leaf'] }, 'a' => 0)

      expect(sorted.keys).to eq(%w[a b])
      expect(sorted['b'].keys).to eq(%w[a z])
      expect(sorted['b']['a'].first.keys).to eq(%w[x y])
      expect(sorted['b']['a'].last).to eq('leaf')
    end
  end

  describe '.deep_stringify' do
    it 'turns symbol keys into strings at every depth without touching values' do
      stringified = described_class.deep_stringify(blank: { too_short: "is too short" },
                                                   list: [{ taken: :keep }])

      expect(stringified).to eq(
        'blank' => { 'too_short' => "is too short" },
        'list' => [{ 'taken' => :keep }]
      )
    end
  end

  describe '.attributes' do
    it 'maps every column of every write-family model to its human attribute name' do
      attributes = described_class.attributes

      expect(attributes.keys).to match_array(%w[task comment email list account campaign contact lead opportunity user])
      expect(attributes['task'].keys).to eq(Task.column_names)
      expect(attributes['list'].keys).to eq(List.column_names)
      expect(attributes['task']['name']).to eq(Task.human_attribute_name('name'))
      expect(attributes['comment']['commentable_id']).to eq(Comment.human_attribute_name('commentable_id'))
    end
  end

  describe '.generate' do
    subject(:catalog) { described_class.generate }

    it 'exports the ActiveModel messages Spring resolves for the AB-272 write validations' do
      expect(catalog.dig('errors', 'messages', 'blank')).to eq("can't be blank")
      expect(catalog.dig('errors', 'messages', 'required')).to eq('must exist')
      expect(catalog.dig('errors', 'messages', 'invalid')).to eq('is invalid')
      expect(catalog.dig('activerecord', 'errors', 'models', 'task', 'attributes', 'name', 'missing_task_name'))
        .to eq(Task.new.tap(&:valid?).errors.messages[:name].first)
      expect(catalog.dig('activerecord', 'errors', 'models', 'task', 'attributes', 'calendar', 'invalid_date'))
        .to eq(I18n.t('activerecord.errors.models.task.attributes.calendar.invalid_date'))
    end

    it 'matches the messages a Rails validation failure actually renders' do
      task = Task.new(name: '', bucket: 'specific_time', calendar: 'not a date')
      task.valid?

      expect(task.errors.messages[:user]).to eq([catalog.dig('errors', 'messages', 'required'),
                                                 catalog.dig('errors', 'messages', 'blank')])
      expect(task.errors.messages[:name])
        .to eq([catalog.dig('activerecord', 'errors', 'models', 'task', 'attributes', 'name', 'missing_task_name')])
      expect(task.errors.messages[:calendar])
        .to eq([catalog.dig('activerecord', 'errors', 'models', 'task', 'attributes', 'calendar', 'invalid_date')])
      expect(List.new.tap(&:valid?).errors.messages)
        .to eq(name: [catalog.dig('errors', 'messages', 'blank')], url: [catalog.dig('errors', 'messages', 'blank')])
      expect(Comment.new.tap(&:valid?).errors.messages[:comment]).to eq([catalog.dig('errors', 'messages', 'blank')])
    end

    it 'is already deep-sorted and only contains string keys' do
      walk = lambda do |value|
        case value
        when Hash
          expect(value.keys).to all(be_a(String))
          expect(value.keys).to eq(value.keys.sort)
          value.each_value { |item| walk.call(item) }
        when Array
          value.each { |item| walk.call(item) }
        end
      end
      walk.call(catalog)
    end
  end
end
