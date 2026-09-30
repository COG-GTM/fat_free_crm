# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../spec_helper')

describe ApplicationHelper do
  it "should be included in the object returned by #helper" do
    included_modules = (class << helper; self; end).send :included_modules
    expect(included_modules).to include(ApplicationHelper)
  end

  describe "link_to_emails" do
    it "should add Bcc: if dropbox address is set" do
      allow(Setting).to receive(:email_dropbox).and_return(address: "drop@example.com")
      expect(helper.link_to_email("hello@example.com")).to eq('<a title="hello@example.com" href="mailto:hello@example.com?bcc=drop@example.com">hello@example.com</a>')
    end

    it "should not add Bcc: if dropbox address is not set" do
      allow(Setting).to receive(:email_dropbox).and_return(address: nil)
      expect(helper.link_to_email("hello@example.com")).to eq('<a title="hello@example.com" href="mailto:hello@example.com">hello@example.com</a>')
    end

    it "should truncate long emails" do
      allow(Setting).to receive(:email_dropbox).and_return(address: nil)
      expect(helper.link_to_email("hello@example.com", 5)).to eq('<a title="hello@example.com" href="mailto:hello@example.com">he...</a>')
    end

    it "should escape HTML entities" do
      allow(Setting).to receive(:email_dropbox).and_return(address: 'dr&op@example.com')
      expect(helper.link_to_email("hell&o@example.com")).to eq('<a title="hell&amp;o@example.com" href="mailto:hell&amp;o@example.com?bcc=dr&amp;op@example.com">hell&amp;o@example.com</a>')
    end
  end

  it "link_to_discard" do
    lead = create(:lead)
    allow(controller.request).to receive(:fullpath).and_return("http://www.example.com/leads/#{lead.id}")

    link = helper.link_to_discard(lead)
    expect(link).to match(%r{leads/#{lead.id}/discard})
    expect(link).to match(/attachment=Lead&amp;attachment_id=#{lead.id}/)
  end

  describe "shown_on_landing_page?" do
    it "should return true for Ajax request made from the asset landing page" do
      allow(controller.request).to receive(:xhr?).and_return(true)
      allow(controller.request).to receive(:referer).and_return("http://www.example.com/leads/123")
      expect(helper.shown_on_landing_page?).to eq(true)
    end

    it "should return true for regular request to display asset landing page" do
      allow(controller.request).to receive(:xhr?).and_return(false)
      allow(controller.request).to receive(:fullpath).and_return("http://www.example.com/leads/123")
      expect(helper.shown_on_landing_page?).to eq(true)
    end

    it "should return false for Ajax request made from page other than the asset landing page" do
      allow(controller.request).to receive(:xhr?).and_return(true)
      allow(controller.request).to receive(:referer).and_return("http://www.example.com/leads")
      expect(helper.shown_on_landing_page?).to eq(false)
    end

    it "should return false for regular request to display page other than asset landing page" do
      allow(controller.request).to receive(:xhr?).and_return(false)
      allow(controller.request).to receive(:fullpath).and_return("http://www.example.com/leads")
      expect(helper.shown_on_landing_page?).to eq(false)
    end
  end

  describe "current_view_name" do
    before(:each) do
      @user = mock_model(User)
      allow(helper).to receive(:current_user).and_return(@user)
      allow(controller).to receive(:action_name).and_return('show')
      allow(controller).to receive(:controller_name).and_return('contacts')
    end

    it "should return the contact 'show' outline stored in the user preferences" do
      expect(@user).to receive(:pref).and_return(contacts_show_view: 'long')
      expect(helper.send(:current_view_name)).to eq('long')
    end
  end

  describe "link_to_phone" do
    it "should return a tel link for a given phone number" do
      expect(helper.link_to_phone("123-456-7890")).to eq('<a href="tel:1234567890">123-456-7890</a>')
    end

    it "should handle phone numbers with a plus sign" do
      expect(helper.link_to_phone("+1 (123) 456-7890")).to eq('<a href="tel:+11234567890">+1 (123) 456-7890</a>')
    end

    it "should return nil if the phone number is blank" do
      expect(helper.link_to_phone("")).to be_nil
      expect(helper.link_to_phone(nil)).to be_nil
    end
  end

  describe "phone_field_with_pattern" do
    let(:user) { create(:user) }
    let(:form) { ActionView::Helpers::FormBuilder.new(:user, user, helper, {}) }

    context "when enforce_international_phone_format is false" do
      before { allow(Setting).to receive(:enforce_international_phone_format).and_return(false) }

      it "should render a normal phone field" do
        expect(helper.phone_field_with_pattern(form, :phone)).to include('type="tel"')
        expect(helper.phone_field_with_pattern(form, :phone)).not_to include('pattern=')
      end
    end

    context "when enforce_international_phone_format is true" do
      before { allow(Setting).to receive(:enforce_international_phone_format).and_return(true) }

      it "should render a phone field with pattern and placeholder" do
        rendered_html = helper.phone_field_with_pattern(form, :phone)
        expect(rendered_html).to include('type="tel"')
        expect(rendered_html).to include('pattern="\\+[0-9]{1,3}\\s?[0-9]{1,14}"')
        expect(rendered_html).to include('placeholder="+1 123 456 7890"')
      end

      it "should not override existing options" do
        rendered_html = helper.phone_field_with_pattern(form, :phone, pattern: "custom", placeholder: "custom")
        expect(rendered_html).to include('pattern="custom"')
        expect(rendered_html).to include('placeholder="custom"')
      end
    end
  end

  describe "web_presence_url" do
    it "should prepend http:// to bare hosts" do
      expect(helper.web_presence_url("example.com/blog")).to eq("http://example.com/blog")
    end

    it "should keep http and https URLs" do
      expect(helper.web_presence_url("https://example.com")).to eq("https://example.com")
      expect(helper.web_presence_url("HTTP://example.com")).to eq("HTTP://example.com")
    end

    it "should keep URLs containing non-ASCII characters" do
      expect(helper.web_presence_url("https://example.com/café")).to eq("https://example.com/café")
      expect(helper.web_presence_url("münchen.example/blog")).to eq("http://münchen.example/blog")
    end

    it "should reject javascript: URLs with an embedded newline" do
      expect(helper.web_presence_url("javascript:alert(document.domain)//\nhttp://x")).to be_nil
    end

    it "should reject javascript: and data: URLs" do
      expect(helper.web_presence_url("javascript:alert(1)")).to be_nil
      expect(helper.web_presence_url("data:text/html,<script>alert(1)</script>")).to be_nil
    end

    it "should reject values containing control characters or whitespace" do
      expect(helper.web_presence_url("http://example.com/\tfoo")).to be_nil
      expect(helper.web_presence_url("http://exa mple.com")).to be_nil
    end

    it "should reject invalid URLs" do
      expect(helper.web_presence_url("http://")).to be_nil
      expect(helper.web_presence_url("http://exa%mple.com")).to be_nil
    end

    it "should return nil for blank values" do
      expect(helper.web_presence_url(nil)).to be_nil
      expect(helper.web_presence_url("  ")).to be_nil
    end
  end

  describe "web_presence_icons" do
    it "should not render a link for an unsafe URL" do
      contact = create(:contact, blog: "javascript:alert(1)//\nhttp://x", twitter: "twitter.com/ffcrm",
                                linkedin: nil, facebook: nil, zoom: nil, teams: nil, signal: nil,
                                instagram: nil, mastodon: nil, bluesky: nil)
      html = helper.web_presence_icons(contact)
      expect(html).not_to include("javascript:")
      expect(html).to include('href="http://twitter.com/ffcrm"')
    end
  end

  describe "web_presence_url edge cases" do
    it "should reject protocol-relative URLs" do
      expect(helper.web_presence_url("//evil.example.com/x")).to be_nil
    end

    it "should reject dangerous schemes regardless of case" do
      expect(helper.web_presence_url("JAVASCRIPT:alert(1)")).to be_nil
      expect(helper.web_presence_url("JaVaScRiPt:alert(1)")).to be_nil
      expect(helper.web_presence_url("vbscript:msgbox(1)")).to be_nil
      expect(helper.web_presence_url("java\tscript:alert(1)")).to be_nil
      expect(helper.web_presence_url("javascript:alert(1)\r\n")).to be_nil
    end

    it "should never return a URL with a non-http(s) scheme" do
      %w[ftp://example.com mailto:someone@example.com file:///etc/passwd javascript://%0aalert(1)].each do |value|
        url = helper.web_presence_url(value)
        expect(url).to match(%r{\Ahttp://}), "expected #{value.inspect} to be neutralised, got #{url.inspect}"
        expect(URI.parse(url)).to be_a(URI::HTTP)
      end
    end

    it "should reject URLs containing HTML metacharacters" do
      expect(helper.web_presence_url('http://example.com/"onmouseover=alert(1)')).to be_nil
      expect(helper.web_presence_url("http://example.com/<script>")).to be_nil
    end

    it "should reject malformed http URLs" do
      expect(helper.web_presence_url("http:example.com")).to be_nil
      expect(helper.web_presence_url("https:///example.com")).to be_nil
      expect(helper.web_presence_url("http://example.com/%zz")).to be_nil
    end

    it "should reject non-ASCII whitespace and unencoded non-ASCII hosts" do
      expect(helper.web_presence_url("http://example.com\u00a0")).to be_nil
      expect(helper.web_presence_url("http://exämple.com")).to be_nil
    end

    it "should strip surrounding whitespace before validating" do
      expect(helper.web_presence_url("  http://example.com/path  ")).to eq("http://example.com/path")
      expect(helper.web_presence_url("\texample.com\n")).to eq("http://example.com")
    end

    it "should preserve port, path, query and fragment of valid URLs" do
      expect(helper.web_presence_url("example.com:8080/a/b?q=1&r=2#frag")).to eq("http://example.com:8080/a/b?q=1&r=2#frag")
      expect(helper.web_presence_url("https://Example.COM/Path")).to eq("https://Example.COM/Path")
    end

    it "should accept IP and IPv6 hosts" do
      expect(helper.web_presence_url("http://192.168.0.1/x")).to eq("http://192.168.0.1/x")
      expect(helper.web_presence_url("http://[::1]/")).to eq("http://[::1]/")
    end

    it "should coerce non-string values to strings" do
      expect(helper.web_presence_url(12_345)).to eq("http://12345")
      expect(helper.web_presence_url(:example)).to eq("http://example")
    end
  end

  describe "web_presence_icons rendering" do
    let(:unsafe_web_presence) do
      {
        blog: "javascript:alert(1)//\nhttp://x",
        linkedin: "JAVASCRIPT:alert(2)",
        facebook: "data:text/html,<script>alert(3)</script>",
        twitter: "//evil.example.com",
        zoom: "vbscript:msgbox(4)",
        teams: 'http://example.com/"onmouseover=alert(5)',
        signal: "http://exa mple.com",
        instagram: "http://",
        mastodon: "javascript:alert(6)\r\n",
        bluesky: "http://example.com/<script>"
      }
    end

    def links(html)
      Nokogiri::HTML::DocumentFragment.parse(html).css("a")
    end

    def popup_links(html)
      links(html).select { |a| a["data-popup"] }
    end

    def expect_no_web_presence_links(html)
      expect(html).not_to include("javascript:")
      expect(html).not_to include("data:")
      expect(html).not_to include("vbscript:")
      expect(html).not_to include("evil.example.com")
      expect(html).not_to include("onmouseover")
      expect(html).not_to include("<script>")
      expect(popup_links(html)).to be_empty
    end

    it "should render nothing but the VCard link for a contact with only unsafe URLs" do
      contact = build_stubbed(:contact, unsafe_web_presence)
      html = helper.web_presence_icons(contact)
      expect_no_web_presence_links(html)
      expect(html).to start_with('<span class="web-presence-icons">')
      expect(links(html).map { |a| [a["href"], a["title"]] }).to eq([[contact_path(contact, format: :vcf), "VCard"]])
    end

    it "should render nothing but the VCard link for a lead with only unsafe URLs" do
      lead = build_stubbed(:lead, unsafe_web_presence)
      html = helper.web_presence_icons(lead)
      expect_no_web_presence_links(html)
      expect(links(html).map { |a| [a["href"], a["title"]] }).to eq([[lead_path(lead, format: :vcf), "VCard"]])
    end

    it "should render no links for an account with only unsafe URLs" do
      account = build_stubbed(:account, unsafe_web_presence.slice(:blog, :linkedin, :facebook, :twitter, :instagram, :mastodon, :bluesky))
      html = helper.web_presence_icons(account)
      expect_no_web_presence_links(html)
      expect(links(html)).to be_empty
    end

    it "should render no links for a user with only unsafe URLs" do
      user = build_stubbed(:user, unsafe_web_presence)
      html = helper.web_presence_icons(user)
      expect_no_web_presence_links(html)
      expect(links(html)).to be_empty
    end

    it "should render an icon link for every site with a valid URL" do
      contact = build_stubbed(:contact,
                              blog: "blog.example.com", linkedin: "https://linkedin.com/in/ffcrm", facebook: "facebook.com/ffcrm",
                              twitter: "twitter.com/ffcrm", zoom: "zoom.us/j/1", teams: "teams.microsoft.com/l/x",
                              signal: "signal.me/#p/+1", instagram: "instagram.com/ffcrm", mastodon: "mastodon.social/@ffcrm",
                              bluesky: "bsky.app/profile/ffcrm")
      html = helper.web_presence_icons(contact)
      rendered = popup_links(html).map { |a| [a["href"], a.at_css("i")["class"]] }
      expect(rendered).to eq([
                               ["http://blog.example.com", "fa fa-external-link"],
                               ["https://linkedin.com/in/ffcrm", "fa fa-linkedin"],
                               ["http://facebook.com/ffcrm", "fa fa-facebook"],
                               ["http://twitter.com/ffcrm", "fa fa-twitter"],
                               ["http://zoom.us/j/1", "fa fa-video-camera"],
                               ["http://teams.microsoft.com/l/x", "fa fa-users"],
                               ["http://signal.me/#p/+1", "fa fa-comment"],
                               ["http://instagram.com/ffcrm", "fa fa-instagram"],
                               ["http://mastodon.social/@ffcrm", "fa fa-retweet"],
                               ["http://bsky.app/profile/ffcrm", "fa fa-cloud"]
                             ])
      expect(links(html).last["title"]).to eq("VCard")
    end

    it "should keep safe links while dropping unsafe ones on the same record" do
      lead = build_stubbed(:lead, unsafe_web_presence.merge(twitter: "twitter.com/ffcrm", facebook: "https://facebook.com/ffcrm"))
      html = helper.web_presence_icons(lead)
      expect(popup_links(html).pluck("href")).to eq(["https://facebook.com/ffcrm", "http://twitter.com/ffcrm"])
      expect(html).not_to include("javascript:")
    end

    it "should HTML-escape the href of valid URLs" do
      contact = build_stubbed(:contact, blog: "example.com/?a=1&b=2", linkedin: nil, facebook: nil, twitter: nil,
                                        zoom: nil, teams: nil, signal: nil, instagram: nil, mastodon: nil, bluesky: nil)
      html = helper.web_presence_icons(contact)
      expect(html).to include('href="http://example.com/?a=1&amp;b=2"')
      expect(html).not_to include("&b=2")
      expect(popup_links(html).first["href"]).to eq("http://example.com/?a=1&b=2")
      expect(popup_links(html).first["title"]).to start_with("Open http://example.com/?a=1&")
    end

    it "should return html_safe markup" do
      expect(helper.web_presence_icons(build_stubbed(:contact))).to be_html_safe
    end

    it "should skip sites the record does not respond to" do
      account = build_stubbed(:account, blog: "example.com")
      expect(account).not_to respond_to(:zoom)
      expect { helper.web_presence_icons(account) }.not_to raise_error
      expect(popup_links(helper.web_presence_icons(account)).pluck("href")).to eq(["http://example.com"])
    end
  end
end
