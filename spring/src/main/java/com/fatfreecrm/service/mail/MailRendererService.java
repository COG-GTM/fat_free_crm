package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(MailRendererService.class);
    private static final String USER_MAILER_FROM = "Fat Free CRM <noreply@fatfreecrm.com>";
    private static final String DEVISE_FROM = "noreply@fatfreecrm.com";
    private static final String NEWLINE_TOKEN = "__FFCRM_MAIL_NEWLINE__";

    private final TemplateEngine htmlEngine;
    private final TemplateEngine textEngine;
    private final MailSettingsService settings;
    private final MailText mailText;

    public MailRendererService(
        @Qualifier("mailHtmlTemplateEngine") TemplateEngine htmlEngine,
        @Qualifier("mailTextTemplateEngine") TemplateEngine textEngine,
        MailSettingsService settings,
        MailText mailText
    ) {
        this.htmlEngine = htmlEngine;
        this.textEngine = textEngine;
        this.settings = settings;
        this.mailText = mailText;
    }

    public RenderedMail assignment(String to, String entityName, String entityType, String entityUrl, String assigner) {
        String body = mailText.text("user_mailer.assigned_entity_notification.body", Map.of(
            "assigner_name", assigner,
            "entity_name", entityName,
            "entity_type", entityType,
            "entity_url", entityUrl));
        Context context = context(Map.of(
            "entityName", entityName,
            "entityType", entityType,
            "entityUrl", entityUrl,
            "assignerName", assigner));
        String subject = mailText.text("user_mailer.assigned_entity_notification.subject", Map.of(
            "entity_name", entityName,
            "entity_type", entityType));
        context.setVariable("body", body);
        String from = settings.smtpFrom();
        return new RenderedMail(
            subject,
            from.isBlank() ? USER_MAILER_FROM : from,
            to,
            "text/html",
            htmlEngine.process("assigned_entity_notification", context) + "\n");
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
        String intro = mailText.text("comment_notification.intro", Map.of(
            "user_full_name", fromUserName == null ? "" : fromUserName,
            "entity_type", entityType,
            "entity_name", entityName));
        String replyInstructions = mailText.text("comment_notification.reply_instructions",
            Map.of("entity", entityType.toLowerCase(Locale.ROOT)));
        Context context = context(Map.of(
            "entityName", entityName,
            "entityType", entityType,
            "fromUserName", fromUserName == null ? "" : fromUserName,
            "entityId", entityId,
            "tags", tags == null ? "" : tags,
            "entityUrl", entityUrl(entityType, entityId),
            "intro", intro,
            "replyInstructions", replyInstructions,
            "comment", sanitizeComment(comment) + "\n"));
        String subject = mailText.text("subscription_mailer.comment_notification.subject", Map.of(
            "entity_type", entityType.toLowerCase(Locale.ROOT),
            "entity_id", entityId,
            "entity_name", entityName));
        if (tags != null && !tags.isBlank()) {
            subject += " " + mailText.text("subscription_mailer.comment_notification.tagged_subject_suffix",
                Map.of("tags", tags));
        }
        return new RenderedMail(subject, settings.commentReplyFrom(fromUserName), to, "text/plain",
            textEngine.process("comment_notification", context));
    }

    public RenderedMail dropboxNotification(
        String to,
        String from,
        String subject,
        String body,
        String mediatorLinks
    ) {
        String localizedSubject = mailText.text("dropbox_notification_subject", Map.of(
            "subject", subject == null ? "" : subject));
        Context context = context(Map.of(
            "subject", subject == null ? "" : subject,
            "body", body == null ? "" : body,
            "mediatorLinks", mediatorLinks == null ? "" : mediatorLinks,
            "intro", mailText.text("dropbox_notification_intro", Map.of()),
            "toLabel", mailText.text("dropbox_notification_to", Map.of()),
            "subjectLabel", mailText.text("subject", Map.of()),
            "bodyLabel", mailText.text("body", Map.of())));
        return new RenderedMail(localizedSubject, from, to, "text/html",
            htmlEngine.process("dropbox_notification", context) + "\n");
    }

    public RenderedMail deviseConfirmation(String to, String token) {
        String action = "confirmation_instructions";
        String base = "devise.mailer." + action + ".";
        Context context = context(Map.of(
            "greeting", mailText.text(base + "greeting", Map.of("recipient", to)),
            "instruction", mailText.text(base + "instruction", Map.of()),
            "action", mailText.text(base + "action", Map.of()),
            "url", deviseUrl("users/confirmation", "confirmation_token", token)));
        return new RenderedMail(mailText.text(base + "subject", Map.of()), DEVISE_FROM, to, "text/html",
            htmlEngine.process("devise_confirmation_instructions", context));
    }

    public RenderedMail deviseResetPassword(String to, String token) {
        String action = "reset_password_instructions";
        String base = "devise.mailer." + action + ".";
        Context context = context(Map.of(
            "greeting", mailText.text(base + "greeting", Map.of("recipient", to)),
            "instruction", mailText.text(base + "instruction", Map.of()),
            "action", mailText.text(base + "action", Map.of()),
            "instruction2", mailText.text(base + "instruction_2", Map.of()),
            "instruction3", mailText.text(base + "instruction_3", Map.of()),
            "url", deviseUrl("users/password/edit", "reset_password_token", token)));
        return new RenderedMail(mailText.text(base + "subject", Map.of()), DEVISE_FROM, to, "text/html",
            htmlEngine.process("devise_reset_password_instructions", context));
    }

    public RenderedMail devisePasswordChange(String to) {
        String base = "devise.mailer.password_change.";
        Context context = context(Map.of(
            "greeting", mailText.text(base + "greeting", Map.of("recipient", to)),
            "message", mailText.text(base + "message", Map.of())));
        return new RenderedMail(mailText.text(base + "subject", Map.of()), DEVISE_FROM, to, "text/html",
            htmlEngine.process("devise_password_change", context));
    }

    private Context context(Map<String, Object> variables) {
        Context context = new Context();
        String locale = settings.locale();
        if (!"en-US".equalsIgnoreCase(locale)) {
            LOGGER.warn("Mail locale {} is unsupported; falling back to en-US", locale);
        }
        context.setLocale(Locale.US);
        variables.forEach(context::setVariable);
        return context;
    }

    private static String sanitizeComment(String comment) {
        String withPreservedNewlines = (comment == null ? "" : comment).replace("\n", NEWLINE_TOKEN);
        String withoutScriptTags = withPreservedNewlines.replaceAll("(?is)</?script[^>]*>", "");
        String escapedBareAmpersands = withoutScriptTags.replaceAll(
            "&(?!#\\d+;|#x[\\da-fA-F]+;|[A-Za-z][A-Za-z\\d]+;)", "&amp;");
        return Jsoup.clean(escapedBareAmpersands, Safelist.basic()).replace(NEWLINE_TOKEN, "\n");
    }

    public String host() {
        return settings.host();
    }

    private String entityUrl(String entityType, long entityId) {
        String host = settings.host();
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
            default -> entityType.toLowerCase(Locale.ROOT) + "s";
        };
        return host.replaceAll("/+$", "") + "/" + path + "/" + entityId;
    }

    private String deviseUrl(String path, String tokenName, String token) {
        String host = settings.host();
        if (host.isBlank()) {
            return "";
        }
        return host.replaceAll("/+$", "") + "/" + path + "?" + tokenName + "="
            + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
    }
}
