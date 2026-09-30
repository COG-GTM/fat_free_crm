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
end
