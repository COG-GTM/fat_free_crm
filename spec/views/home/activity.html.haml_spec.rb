# frozen_string_literal: true

require File.expand_path('../../spec_helper', __dir__)

describe "home/_activity" do
  include HomeHelper

  before do
    login
  end

  def activity_for(item, user: current_user, event: "create")
    build_stubbed(:version, item: item, user: user, event: event)
  end

  it "matches the original partial for a collection of mixed events and item types" do
    account = build_stubbed(:account)
    comment = build_stubbed(:comment, commentable: account, comment: "A dashboard comment <with markup>")
    activities = [
      activity_for(account),
      activity_for(build_stubbed(:contact), event: "update"),
      activity_for(build_stubbed(:task), event: "destroy"),
      activity_for(account, event: "custom_dashboard_event"),
      activity_for(account, user: nil),
      activity_for(comment)
    ]
    activities.last.related = account
    allow(Setting).to receive(:comments_visible_on_dashboard).and_return(true)
    original = Rails.root.join('spec/fixtures/activity_original.html.haml').read
    expected = activities.map do |activity|
      view.render(inline: original, type: :haml, locals: { activity: activity })
    end.join

    render partial: 'home/activity', collection: activities

    expect(rendered).to eq(expected)
    expect(rendered).to include("A dashboard comment &lt;with markup&gt;")
    expect(rendered).to include("version_#{activities[3].id}")
    expect(rendered).not_to include("version_#{activities[4].id}")
  end

  it "renders an empty collection without building user markup" do
    expect(view).not_to receive(:avatar_for)
    expect(view).not_to receive(:user_path)

    expect(view.render(partial: 'home/activity', collection: [])).to be_nil
  end

  it "builds user markup fewer times than rows and keeps each user's markup separate" do
    users = [current_user, create(:user, first_name: "Grace", last_name: "Hopper")]
    activities = users.flat_map do |user|
      Array.new(4) { activity_for(build_stubbed(:account), user: user) }
    end
    users.each do |user|
      expect(view).to receive(:avatar_for).with(user, size: :thumb).once.and_return("avatar-#{user.id}")
      expect(view).to receive(:user_path).with(user).twice.and_call_original
    end

    render partial: 'home/activity', collection: activities

    rows = Nokogiri::HTML.fragment(rendered).css('li')
    activities.zip(rows).each do |activity, row|
      user = activity.user
      links = row.css("a[href='#{user_path(user)}']")
      expect(links.map(&:text)).to eq(["avatar-#{user.id}", user.full_name])
    end
  end
end
