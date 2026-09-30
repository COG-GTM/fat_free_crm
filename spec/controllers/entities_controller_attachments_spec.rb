# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../spec_helper')

# Build a Shared entity with the given user/group permissions. Access must be
# set before the permission ids because user_ids= depends on the access value.
def create_shared(factory, user_ids: [], group_ids: [], **attributes)
  record = build(factory, **attributes, access: "Shared")
  record.user_ids = user_ids
  record.group_ids = group_ids
  record.save!
  record
end

describe EntitiesController do
  # Covers EntitiesController#attach / #discard authorization introduced by
  # EntitiesController#find_attachment, exercised through a concrete controller.
  describe AccountsController do
    before do
      login
      set_current_tab(:accounts)
    end

    let(:other_user) { create(:user) }

    describe "ATTACHABLE_CLASSES" do
      it "should only allow contacts, leads, opportunities and tasks" do
        expect(EntitiesController::ATTACHABLE_CLASSES).to eq(%w[Contact Lead Opportunity Task])
        expect(EntitiesController::ATTACHABLE_CLASSES).to be_frozen
      end
    end

    describe "find_attachment" do
      before { @task = create(:task, user: current_user) }

      it "should resolve plural table names to the attachable class" do
        expect(controller.send(:find_attachment, "tasks", @task.id)).to eq(@task)
      end

      it "should resolve singular class names to the attachable class" do
        expect(controller.send(:find_attachment, "Task", @task.id)).to eq(@task)
      end

      it "should treat non-attachable classes as not found" do
        account = create(:account, user: current_user)
        %w[accounts Account campaigns users User].each do |asset|
          expect { controller.send(:find_attachment, asset, account.id) }.to raise_error(ActiveRecord::RecordNotFound)
        end
      end

      it "should treat unknown constants as not found instead of raising NameError" do
        expect { controller.send(:find_attachment, "widgets", @task.id) }.to raise_error(ActiveRecord::RecordNotFound)
        expect { controller.send(:find_attachment, "no_such_thing", @task.id) }.to raise_error(ActiveRecord::RecordNotFound)
      end

      it "should treat nil and blank asset names as not found" do
        expect { controller.send(:find_attachment, nil, @task.id) }.to raise_error(ActiveRecord::RecordNotFound)
        expect { controller.send(:find_attachment, "", @task.id) }.to raise_error(ActiveRecord::RecordNotFound)
      end

      it "should raise RecordNotFound when the record does not exist" do
        expect { controller.send(:find_attachment, "tasks", @task.id + 42) }.to raise_error(ActiveRecord::RecordNotFound)
        expect { controller.send(:find_attachment, "tasks", nil) }.to raise_error(ActiveRecord::RecordNotFound)
      end

      it "should not find a record of one attachable class by an id of another" do
        contact = create(:contact, user: current_user)
        Task.where(id: contact.id).destroy_all
        expect { controller.send(:find_attachment, "tasks", contact.id) }.to raise_error(ActiveRecord::RecordNotFound)
      end

      it "should raise AccessDenied when the user cannot update the record" do
        private_contact = create(:contact, user: other_user, access: "Private")
        expect { controller.send(:find_attachment, "contacts", private_contact.id) }.to raise_error(CanCan::AccessDenied)
      end
    end

    # PUT /accounts/1/attach                                                 AJAX
    #----------------------------------------------------------------------------
    describe "responding to PUT attach" do
      before { @account = create(:account, user: current_user) }

      def attach(attachment, assets: attachment.class.name.tableize, format: nil)
        params = { id: @account.id, assets: assets, asset_id: attachment.id }
        params[:format] = format if format
        put :attach, params: params, xhr: true
      end

      describe "authorization through Ability" do
        it "should attach a Public contact owned by another user" do
          contact = create(:contact, user: other_user, access: "Public")
          attach(contact)
          expect(assigns[:attached]).to eq([contact])
          expect(contact.reload.account).to eq(@account)
          expect(response).to render_template("entities/attach")
        end

        it "should attach a Shared contact the user has been granted permission to" do
          contact = create_shared(:contact, user: other_user, user_ids: [current_user.id])
          attach(contact)
          expect(assigns[:attached]).to eq([contact])
          expect(contact.reload.account).to eq(@account)
        end

        it "should attach a Shared contact the user's group has been granted permission to" do
          group = Group.create!(name: "Sales")
          current_user.groups << group
          contact = create_shared(:contact, user: other_user, group_ids: [group.id])
          attach(contact)
          expect(assigns[:attached]).to eq([contact])
          expect(contact.reload.account).to eq(@account)
        end

        it "should not attach a Shared contact the user has no permission to" do
          contact = create_shared(:contact, user: other_user, user_ids: [other_user.id])
          attach(contact)
          expect(assigns[:attached]).to eq(nil)
          expect(contact.reload.account).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should attach a Private contact assigned to the current user" do
          contact = create(:contact, user: other_user, assigned_to: current_user.id, access: "Private")
          attach(contact)
          expect(assigns[:attached]).to eq([contact])
          expect(contact.reload.account).to eq(@account)
        end

        it "should attach a task assigned to the current user but owned by another user" do
          task = create(:task, user: other_user, assigned_to: current_user.id)
          attach(task)
          expect(assigns[:attached]).to eq([task])
          expect(task.reload.asset).to eq(@account)
        end

        it "should attach a task completed by the current user" do
          task = create(:completed_task, user: other_user, completed_by: current_user.id)
          attach(task)
          expect(assigns[:attached]).to eq([task])
          expect(task.reload.asset).to eq(@account)
        end

        it "should not attach another user's task that is neither assigned to nor completed by the current user" do
          task = create(:task, user: other_user, assigned_to: create(:user).id)
          attach(task)
          expect(assigns[:attached]).to eq(nil)
          expect(task.reload.asset).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should let an admin attach another user's Private contact" do
          current_user.update_attribute(:admin, true)
          contact = create(:contact, user: other_user, access: "Private")
          attach(contact)
          expect(assigns[:attached]).to eq([contact])
          expect(contact.reload.account).to eq(@account)
        end

        it "should deny access even when the parent entity is owned by the current user" do
          contact = create(:contact, user: other_user, access: "Private")
          attach(contact)
          expect(@account.reload.contacts).to be_empty
          expect(contact.reload.account).to eq(nil)
          expect(response.body).to eq("window.location.reload();")
        end

        it "should still deny attaching the user's own record to a parent entity the user cannot update" do
          private_account = create(:account, user: other_user, access: "Private")
          contact = create(:contact, user: current_user)
          put :attach, params: { id: private_account.id, assets: "contacts", asset_id: contact.id }, xhr: true
          expect(contact.reload.account).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end
      end

      describe "restricting attachable classes" do
        it "should not attach an existing entity of a non-attachable class" do
          other_account = create(:account, user: current_user)
          attach(other_account, assets: "accounts")
          expect(assigns[:attachment]).to eq(nil)
          expect(assigns[:attached]).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_asset_not_available, value: "account"))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should not attach a campaign" do
          campaign = create(:campaign, user: current_user)
          attach(campaign, assets: "campaigns")
          expect(assigns[:attached]).to eq(nil)
          expect(response.body).to eq("window.location.reload();")
        end

        it "should respond with a not-available warning for an unknown class instead of an error" do
          put :attach, params: { id: @account.id, assets: "widgets", asset_id: 1 }, xhr: true
          expect(assigns[:attached]).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_asset_not_available, value: "account"))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should respond with a not-available warning when the assets parameter is missing" do
          put :attach, params: { id: @account.id, asset_id: 1 }, xhr: true
          expect(assigns[:attached]).to eq(nil)
          expect(flash[:warning]).not_to eq(nil)
          expect(response.body).to eq("window.location.reload();")
        end
      end

      describe "non-JS formats" do
        it "should respond with 401 JSON when the attachment is not authorized" do
          contact = create(:contact, user: other_user, access: "Private")
          attach(contact, format: :json)
          expect(response).to have_http_status(:unauthorized)
          expect(response.body).to eq(I18n.t(:msg_not_authorized))
          expect(contact.reload.account).to eq(nil)
        end

        it "should respond with 404 JSON for a non-attachable class" do
          attach(create(:account, user: current_user), assets: "accounts", format: :json)
          expect(response).to have_http_status(:not_found)
          expect(response.body).to eq(I18n.t(:msg_asset_not_available, value: "account"))
        end

        it "should respond with 401 XML when the attachment is not authorized" do
          contact = create(:contact, user: other_user, access: "Private")
          attach(contact, format: :xml)
          expect(response).to have_http_status(:unauthorized)
          expect(response.body).to include(I18n.t(:msg_not_authorized))
        end
      end
    end

    # POST /accounts/1/discard                                               AJAX
    #----------------------------------------------------------------------------
    describe "responding to POST discard" do
      before { @account = create(:account, user: current_user) }

      def discard(attachment, name: attachment.class.name, format: nil)
        params = { id: @account.id, attachment: name, attachment_id: attachment.id }
        params[:format] = format if format
        post :discard, params: params, xhr: true
      end

      describe "authorization through Ability" do
        it "should discard a Public contact owned by another user" do
          contact = create(:contact, user: other_user, access: "Public", account: @account)
          discard(contact)
          expect(contact.reload.account).to eq(nil)
          expect(response).to render_template("entities/discard")
        end

        it "should discard a Shared contact the user has been granted permission to" do
          contact = create_shared(:contact, user: other_user, account: @account, user_ids: [current_user.id])
          discard(contact)
          expect(contact.reload.account).to eq(nil)
          expect(response).to render_template("entities/discard")
        end

        it "should not discard a Shared contact the user has no permission to" do
          contact = create_shared(:contact, user: other_user, account: @account, user_ids: [other_user.id])
          discard(contact)
          expect(contact.reload.account).to eq(@account)
          expect(@account.reload.contacts).to include(contact)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should discard a task assigned to the current user but owned by another user" do
          task = create(:task, user: other_user, assigned_to: current_user.id, asset: @account)
          discard(task)
          expect(task.reload.asset).to eq(nil)
          expect(response).to render_template("entities/discard")
        end

        it "should not discard another user's task attached to the user's own account" do
          task = create(:task, user: other_user, asset: @account)
          discard(task)
          expect(task.reload.asset).to eq(@account)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should let an admin discard another user's Private contact" do
          current_user.update_attribute(:admin, true)
          contact = create(:contact, user: other_user, access: "Private", account: @account)
          discard(contact)
          expect(contact.reload.account).to eq(nil)
          expect(response).to render_template("entities/discard")
        end

        it "should still deny discarding from a parent entity the user cannot update" do
          private_account = create(:account, user: other_user, access: "Private")
          contact = create(:contact, user: current_user, account: private_account)
          post :discard, params: { id: private_account.id, attachment: "Contact", attachment_id: contact.id }, xhr: true
          expect(contact.reload.account).to eq(private_account)
          expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
          expect(response.body).to eq("window.location.reload();")
        end
      end

      describe "restricting attachable classes" do
        it "should not discard using a non-attachable class" do
          other_account = create(:account, user: current_user)
          discard(other_account, name: "Account")
          expect(assigns[:attachment]).to eq(nil)
          expect(flash[:warning]).to eq(I18n.t(:msg_asset_not_available, value: "account"))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should not discard using a user class" do
          discard(current_user, name: "User")
          expect(assigns[:attachment]).to eq(nil)
          expect(flash[:warning]).not_to eq(nil)
          expect(response.body).to eq("window.location.reload();")
        end

        it "should respond with a not-available warning for an unknown class instead of an error" do
          post :discard, params: { id: @account.id, attachment: "Widget", attachment_id: 1 }, xhr: true
          expect(flash[:warning]).to eq(I18n.t(:msg_asset_not_available, value: "account"))
          expect(response.body).to eq("window.location.reload();")
        end

        it "should accept the plural form of an attachable class name" do
          contact = create(:contact, user: current_user, account: @account)
          discard(contact, name: "contacts")
          expect(contact.reload.account).to eq(nil)
          expect(response).to render_template("entities/discard")
        end
      end

      describe "non-JS formats" do
        it "should respond with 401 JSON when the attachment is not authorized" do
          contact = create(:contact, user: other_user, access: "Private", account: @account)
          discard(contact, format: :json)
          expect(response).to have_http_status(:unauthorized)
          expect(response.body).to eq(I18n.t(:msg_not_authorized))
          expect(contact.reload.account).to eq(@account)
        end

        it "should respond with 404 JSON for a non-attachable class" do
          discard(@account, name: "Account", format: :json)
          expect(response).to have_http_status(:not_found)
        end
      end
    end
  end

  # Campaign attachments mutate the attached record (campaign_id and the
  # campaign's leads/opportunities counters), so make sure denied requests leave
  # both sides untouched.
  describe CampaignsController do
    before do
      login
      set_current_tab(:campaigns)
      @campaign = create(:campaign, user: current_user, leads_count: 0, opportunities_count: 0)
    end

    let(:other_user) { create(:user) }

    describe "responding to PUT attach" do
      it "should attach a Public lead owned by another user and update the counter" do
        lead = create(:lead, user: other_user, access: "Public", campaign: nil)
        put :attach, params: { id: @campaign.id, assets: "leads", asset_id: lead.id }, xhr: true
        expect(assigns[:attached]).to eq([lead])
        expect(lead.reload.campaign).to eq(@campaign)
        expect(@campaign.reload.leads_count).to eq(1)
      end

      it "should not attach a Private lead owned by another user nor touch the counter" do
        lead = create(:lead, user: other_user, access: "Private", campaign: nil)
        put :attach, params: { id: @campaign.id, assets: "leads", asset_id: lead.id }, xhr: true
        expect(assigns[:attached]).to eq(nil)
        expect(lead.reload.campaign).to eq(nil)
        expect(@campaign.reload.leads_count).to eq(0)
        expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
        expect(response.body).to eq("window.location.reload();")
      end

      it "should not attach a Private opportunity owned by another user nor touch the counter" do
        opportunity = create(:opportunity, user: other_user, access: "Private", campaign: nil)
        put :attach, params: { id: @campaign.id, assets: "opportunities", asset_id: opportunity.id }, xhr: true
        expect(assigns[:attached]).to eq(nil)
        expect(opportunity.reload.campaign).to eq(nil)
        expect(@campaign.reload.opportunities_count).to eq(0)
        expect(response.body).to eq("window.location.reload();")
      end
    end

    describe "responding to POST discard" do
      it "should not discard a Private lead owned by another user nor touch the counter" do
        lead = create(:lead, user: other_user, access: "Private", campaign: @campaign)
        expect(@campaign.reload.leads_count).to eq(1)
        post :discard, params: { id: @campaign.id, attachment: "Lead", attachment_id: lead.id }, xhr: true
        expect(lead.reload.campaign).to eq(@campaign)
        expect(@campaign.reload.leads_count).to eq(1)
        expect(flash[:warning]).to eq(I18n.t(:msg_not_authorized))
        expect(response.body).to eq("window.location.reload();")
      end

      it "should discard a Shared lead the user has permission to and update the counter" do
        lead = create_shared(:lead, user: other_user, campaign: @campaign, user_ids: [current_user.id])
        expect(@campaign.reload.leads_count).to eq(1)
        post :discard, params: { id: @campaign.id, attachment: "Lead", attachment_id: lead.id }, xhr: true
        expect(lead.reload.campaign).to eq(nil)
        expect(@campaign.reload.leads_count).to eq(0)
        expect(response).to render_template("entities/discard")
      end
    end
  end
end
