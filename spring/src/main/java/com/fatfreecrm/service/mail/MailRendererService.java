package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed template engines are intentionally retained by this service."
)
public class MailRendererService {

    private final TemplateEngine htmlEngine;
    private final TemplateEngine textEngine;
    private final MailSettingsService settings;
    private final String host;

    public MailRendererService(
        @Qualifier("mailHtmlTemplateEngine") TemplateEngine htmlEngine,
        @Qualifier("mailTextTemplateEngine") TemplateEngine textEngine,
        MailSettingsService settings
    ) {
        this.htmlEngine = htmlEngine;
        this.textEngine = textEngine;
        this.settings = settings;
        this.host = settings.host();
    }

    public RenderedMail assignment(String to, String entityName, String entityType, String entityUrl, String assigner) {
        Context context = context(Map.of(
            "entityName", entityName,
            "entityType", entityType,
            "entityUrl", entityUrl,
            "assignerName", assigner));
        String subject = "Fat Free CRM: You have been assigned " + entityName + " " + entityType;
        return new RenderedMail(
            to,
            settings.smtpFrom(),
            subject,
            textEngine.process("assigned_entity_notification", context),
            htmlEngine.process("assigned_entity_notification", context));
    }

    public RenderedMail commentNotification(
        String to,
        String fromUserName,
        String entityName,
        String entityType,
        long entityId,
        String tags,
        String comment
    ) {
        Context context = context(Map.of(
            "entityName", entityName,
            "entityType", entityType,
            "fromUserName", fromUserName == null ? "" : fromUserName,
            "entityId", entityId,
            "tags", tags == null ? "" : tags,
            "entityUrl", entityUrl(entityType, entityId),
            "comment", comment == null ? "" : comment));
        String subject = "RE: [" + entityType.toLowerCase() + ":" + entityId + "] " + entityName;
        if (tags != null && !tags.isBlank()) {
            subject += " (" + tags + ")";
        }
        return new RenderedMail(to, settings.commentReplyFrom(fromUserName), subject,
            textEngine.process("comment_notification", context), htmlEngine.process("comment_notification", context));
    }

    public RenderedMail dropboxNotification(
        String to,
        String from,
        String subject,
        String body,
        String mediatorLinks,
        String localizedSubject
    ) {
        Context context = context(Map.of(
            "subject", subject == null ? "" : subject,
            "body", body == null ? "" : body,
            "mediatorLinks", mediatorLinks == null ? "" : mediatorLinks));
        return new RenderedMail(to, from, localizedSubject, textEngine.process("dropbox_notification", context),
            htmlEngine.process("dropbox_notification", context));
    }

    public RenderedMail devise(String to, String from, String subject, String body) {
        Context context = context(Map.of("body", body == null ? "" : body));
        return new RenderedMail(to, from, subject, textEngine.process("devise_notification", context),
            htmlEngine.process("devise_notification", context));
    }

    private Context context(Map<String, Object> variables) {
        Context context = new Context();
        context.setLocale(java.util.Locale.forLanguageTag(settings.locale()));
        variables.forEach(context::setVariable);
        return context;
    }

    public String host() {
        return host;
    }

    private String entityUrl(String entityType, long entityId) {
        if (host.isBlank()) {
            return "";
        }
        String path = switch (entityType) {
            case "Account" -> "accounts";
            case "Campaign" -> "campaigns";
            case "Contact" -> "contacts";
            case "Lead" -> "leads";
            case "Opportunity" -> "opportunities";
            case "Task" -> "tasks";
            default -> entityType.toLowerCase(java.util.Locale.ROOT) + "s";
        };
        return host.replaceAll("/+$", "") + "/" + path + "/" + entityId;
    }
}
