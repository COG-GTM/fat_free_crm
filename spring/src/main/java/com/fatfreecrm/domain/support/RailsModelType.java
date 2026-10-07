package com.fatfreecrm.domain.support;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Activity;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Avatar;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.FieldGroup;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.ResearchTool;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;

public enum RailsModelType {
    ACCOUNT("Account", Account.class),
    ACCOUNT_CONTACT("AccountContact", AccountContact.class),
    ACCOUNT_OPPORTUNITY("AccountOpportunity", AccountOpportunity.class),
    ACTIVITY("Activity", Activity.class),
    ADDRESS("Address", Address.class),
    AVATAR("Avatar", Avatar.class),
    CAMPAIGN("Campaign", Campaign.class),
    COMMENT("Comment", Comment.class),
    CONTACT("Contact", Contact.class),
    CONTACT_OPPORTUNITY("ContactOpportunity", ContactOpportunity.class),
    EMAIL("Email", Email.class),
    FIELD("Field", Field.class),
    FIELD_GROUP("FieldGroup", FieldGroup.class),
    GROUP("Group", Group.class),
    LEAD("Lead", Lead.class),
    LIST("List", SavedList.class),
    OPPORTUNITY("Opportunity", Opportunity.class),
    PERMISSION("Permission", Permission.class),
    PREFERENCE("Preference", Preference.class),
    RESEARCH_TOOL("ResearchTool", ResearchTool.class),
    SETTING("Setting", Setting.class),
    TAG("Tag", Tag.class),
    TAGGING("Tagging", Tagging.class),
    TASK("Task", Task.class),
    USER("User", User.class),
    VERSION("Version", Version.class);

    private final String railsName;
    private final Class<?> entityClass;

    RailsModelType(String railsName, Class<?> entityClass) {
        this.railsName = railsName;
        this.entityClass = entityClass;
    }

    public String railsName() {
        return railsName;
    }

    public Class<?> entityClass() {
        return entityClass;
    }

    public static RailsModelType fromRailsName(String name) {
        for (RailsModelType type : values()) {
            if (type.railsName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Rails model type: " + name);
    }
}
