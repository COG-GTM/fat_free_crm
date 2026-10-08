# frozen_string_literal: true

require "json"
require "warden"
require "active_support/testing/time_helpers"

TASK_MATRIX_NOW = Time.utc(2026, 3, 12, 2, 30, 0)

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails /tasks.json buckets, show and auto_complete on a frozen clock as a parity matrix for Spring"
    task task_buckets_matrix: :environment do
      abort "ffcrm:migration:task_buckets_matrix requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/search/tasks_search_matrix.json"))

      non_empty = %w[users tasks settings].select do |table|
        ActiveRecord::Base.connection.select_value(
          "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
        ).to_i.positive?
      end
      abort "ffcrm:migration:task_buckets_matrix requires empty tables; found rows in: #{non_empty.join(', ')}" unless non_empty.empty?

      result = nil
      zone = Time.zone
      ActiveRecord::Base.transaction(requires_new: true) do
        extend ActiveSupport::Testing::TimeHelpers
        include Warden::Test::Helpers

        Warden.test_mode!
        travel_to(TASK_MATRIX_NOW) do
          users = {
            "alice" => search_matrix_user(9001, "alice", "alice@task-matrix.test", admin: false),
            "bob" => search_matrix_user(9002, "bob", "bob@task-matrix.test", admin: false),
            "carol" => search_matrix_user(9003, "carol", "carol@task-matrix.test", admin: false)
          }
          task_matrix_seed(users)
          connection = ActiveRecord::Base.connection
          corpus = %w[users tasks].index_with do |table|
            connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
          end
          cases = task_matrix_cases.map { |name, user, path, params, offset| task_matrix_run_case(users.fetch(user), name, path, params, offset) }
          result = {
            "generated_by" => "bundle exec rake ffcrm:migration:task_buckets_matrix " \
                              "OUTPUT=spring/src/test/resources/search/tasks_search_matrix.json",
            "now" => TASK_MATRIX_NOW.iso8601,
            "corpus" => corpus,
            "cases" => cases
          }
        ensure
          travel_back
        end

        raise ActiveRecord::Rollback
      end
      Warden.test_reset!
      Time.zone = zone

      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output}"
    end
  end
end

def task_matrix_seed(users)
  alice, bob, carol = users.values_at("alice", "bob", "carol")
  tasks = [
    # id, name, owner, assignee, bucket, due_at (UTC), completed_at (UTC), completed_by
    [9601, "Overdue report", alice, nil, "overdue", "2026-03-10 15:00", nil, nil],
    [9602, "Call ASAP", alice, nil, "due_asap", nil, nil, nil],
    [9603, "Boundary evening", alice, nil, "due_today", "2026-03-11 23:00", nil, nil],
    [9604, "Today afternoon", alice, nil, "due_today", "2026-03-12 18:00", nil, nil],
    [9605, "Tomorrow review", alice, nil, "due_tomorrow", "2026-03-13 10:00", nil, nil],
    [9606, "This week prep", alice, nil, "due_this_week", "2026-03-14 10:00", nil, nil],
    [9607, "Next week demo", alice, nil, "due_next_week", "2026-03-18 10:00", nil, nil],
    [9608, "Later dated", alice, nil, "due_later", "2026-04-02 10:00", nil, nil],
    [9609, "Later undated", alice, nil, "due_later", nil, nil, nil],
    [9610, "Orphan bucket", alice, nil, "due_this_week", nil, nil, nil],
    [9611, "Boundary midnight", alice, nil, "due_today", "2026-03-12 00:00", nil, nil],
    [9612, "Weekly sync", alice, nil, "overdue", "2026-03-01 09:00", nil, nil],
    [9613, "Weekly sync", alice, nil, "overdue", "2026-02-20 09:00", nil, nil],
    [9614, "Sunday boundary", alice, nil, "due_next_week", "2026-03-22 23:30", nil, nil],
    [9615, "100% done_ish", alice, nil, "due_later", "2026-05-01 10:00", nil, nil],
    [9620, "Delegated today", alice, bob, "due_today", "2026-03-12 12:00", nil, nil],
    [9621, "Delegated asap", alice, bob, "due_asap", nil, nil, nil],
    [9622, "Self assigned", alice, alice, "due_tomorrow", "2026-03-13 15:00", nil, nil],
    [9623, "Bob asks alice", bob, alice, "due_tomorrow", "2026-03-13 11:00", nil, nil],
    [9624, "Bob private", bob, nil, "due_today", "2026-03-12 13:00", nil, nil],
    [9625, "Carol private", carol, nil, "due_asap", nil, nil, nil],
    [9630, "Completed today", alice, nil, "due_asap", nil, "2026-03-12 01:00", alice],
    [9631, "Completed yesterday", alice, nil, "due_asap", nil, "2026-03-11 05:00", alice],
    [9632, "Completed this week", alice, nil, "due_asap", nil, "2026-03-10 05:00", alice],
    [9633, "Completed last week", alice, nil, "due_asap", nil, "2026-03-04 12:00", alice],
    [9634, "Completed this month", alice, nil, "due_asap", nil, "2026-03-01 12:00", alice],
    [9635, "Completed last month", alice, nil, "due_asap", nil, "2026-02-14 12:00", alice],
    [9636, "Completed long ago", alice, nil, "due_asap", nil, "2026-01-05 12:00", alice],
    [9637, "Bob task alice did", bob, alice, "due_asap", nil, "2026-03-11 20:00", alice],
    [9638, "Delegated done", alice, bob, "due_asap", nil, "2026-03-12 00:30", bob],
    [9639, "Carol task alice closed", carol, nil, "due_asap", nil, "2026-03-11 21:00", alice],
    [9640, "Boundary completed", alice, nil, "due_asap", nil, "2026-03-11 23:59", alice]
  ]
  utc = Time.find_zone("UTC")
  tasks.each do |row|
    id, name, owner, assignee, bucket, due_at, completed_at, completed_by = row
    task = Task.new(
      id: id, name: name, user: owner, assigned_to: assignee&.id, bucket: bucket, category: "call",
      completed_at: completed_at && utc.parse(completed_at), completed_by: completed_by&.id,
      created_at: Time.utc(2026, 1, 1), updated_at: Time.utc(2026, 1, 1)
    )
    task.save!(validate: false)
    # Task#set_due_date derives due_at from the bucket on create; store the exact calendar due time instead.
    task.update_columns(due_at: due_at && utc.parse(due_at))
  end
end

def task_matrix_cases
  # name, user, path, params, browser timezone offset (JavaScript getTimezoneOffset minutes; nil = Rails default zone)
  views = [["default", {}], ["pending", { "view" => "pending" }], ["assigned", { "view" => "assigned" }],
           ["completed", { "view" => "completed" }]]
  cases = views.map { |name, params| ["alice_#{name}", "alice", "/tasks.json", params, nil] }
  cases += views.drop(1).map { |name, params| ["bob_#{name}", "bob", "/tasks.json", params, nil] }
  cases += [
    ["alice_bogus_view", "alice", "/tasks.json", { "view" => "bogus" }, nil],
    ["alice_pending_utc_minus3", "alice", "/tasks.json", { "view" => "pending" }, 180],
    ["alice_completed_utc_minus3", "alice", "/tasks.json", { "view" => "completed" }, 180],
    ["alice_pending_utc_plus1", "alice", "/tasks.json", { "view" => "pending" }, -60],
    ["alice_completed_utc_plus1", "alice", "/tasks.json", { "view" => "completed" }, -60],
    ["alice_pending_utc_plus13", "alice", "/tasks.json", { "view" => "pending" }, -780],
    ["alice_assigned_utc_minus10", "alice", "/tasks.json", { "view" => "assigned" }, 600],
    ["carol_completed", "carol", "/tasks.json", { "view" => "completed" }, nil],
    ["show_owner", "alice", "/tasks/9601.json", {}, nil],
    ["show_assignee", "alice", "/tasks/9623.json", {}, nil],
    ["show_completed_by_only", "alice", "/tasks/9639.json", {}, nil],
    ["show_out_of_scope", "alice", "/tasks/9624.json", {}, nil],
    ["show_missing", "alice", "/tasks/9999999.json", {}, nil],
    ["autocomplete_all", "alice", "/tasks/auto_complete.json", { "term" => "" }, nil],
    ["autocomplete_call", "alice", "/tasks/auto_complete.json", { "term" => "call" }, nil],
    ["autocomplete_boundary", "alice", "/tasks/auto_complete.json", { "term" => "BOUNDARY" }, nil],
    ["autocomplete_stripped", "alice", "/tasks/auto_complete.json", { "term" => "100%" }, nil],
    ["autocomplete_underscore", "alice", "/tasks/auto_complete.json", { "term" => "done_" }, nil],
    ["autocomplete_exclude_id", "alice", "/tasks/auto_complete.json", { "term" => "call", "related" => "9602" }, nil],
    ["autocomplete_none", "alice", "/tasks/auto_complete.json", { "term" => "zzz" }, nil]
  ]
  cases
end

def task_matrix_run_case(user, name, path, params, offset)
  session = ActionDispatch::Integration::Session.new(Rails.application)
  session.host! "localhost"
  login_as(user, scope: :user)
  # set_context only assigns Time.zone when the session has an offset, so reset the thread-local zone per case.
  Time.zone = Time.zone_default
  session.get "/home/timezone", params: { offset: offset.to_s } unless offset.nil?
  session.get path, params: params
  status = session.response.status
  body = status == 200 ? JSON.parse(session.response.body) : nil
  zone = offset.nil? ? Time.zone_default : ActiveSupport::TimeZone[offset * -60]
  result = {
    "name" => name,
    "user_id" => user.id,
    "path" => path,
    "params" => params,
    "status" => status,
    "time_zone" => zone.tzinfo.identifier
  }
  result["timezone_offset"] = offset unless offset.nil?
  result["body"] = body
  result
end
