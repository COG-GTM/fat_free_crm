package com.fatfreecrm.service.mailprocessor;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.fatfreecrm.service.history.RailsRowAttributes;
import com.fatfreecrm.service.mail.MailSettingsService;
import jakarta.mail.Message;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class DropboxProcessor extends MailProcessorBase {

    private static final Pattern KEYWORD = Pattern.compile(
        "(?i)(account|campaign|contact|lead|opportunity)[^a-zA-Z0-9]+(.+)$");
    private static final Pattern FORWARDED_RECIPIENT = Pattern.compile(
        "\\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,4})\\b");

    private final RailsRowAttributes rowAttributes;
    private final VersionRecorder versionRecorder;

    public DropboxProcessor(
        MailSettingsService settings,
        UserRepository userRepository,
        EntityManager entityManager,
        JdbcTemplate jdbcTemplate,
        RailsRowAttributes rowAttributes,
        VersionRecorder versionRecorder,
        JobsOwner jobsOwner,
        ImapClient imapClient,
        PlatformTransactionManager transactionManager
    ) {
        super(settings, userRepository, entityManager, jdbcTemplate, jobsOwner, imapClient, transactionManager);
        this.rowAttributes = rowAttributes;
        this.versionRecorder = versionRecorder;
    }

    public void process(boolean dryRun) {
        processInbox("email_dropbox", dryRun, this::processMessage);
    }

    private void processMessage(Message message, User sender, Map<String, Object> config) throws Exception {
        String body = plainTextBody(message);
        List<String> keyword = explicitKeyword(body);
        if (keyword != null) {
            findOrCreateAndAttach(message, sender, keyword.get(0), keyword.get(1), config);
            return;
        }
        List<String> recipients = recipients(message, config);
        for (String recipient : recipients) {
            if (findAndAttach(message, sender, recipient, config)) {
                return;
            }
        }
        String forwarded = firstForwardedRecipient(body);
        if (forwarded != null && findAndAttach(message, sender, forwarded, config)) {
            return;
        }
        if (!recipients.isEmpty()) {
            createAndAttach(message, sender, recipients.get(0), config);
        } else if (forwarded != null) {
            createAndAttach(message, sender, forwarded, config);
        }
    }

    private void findOrCreateAndAttach(
        Message message,
        User sender,
        String type,
        String name,
        Map<String, Object> config
    ) throws Exception {
        Class<?> entityType = entityClass(type);
        if (entityType == null) {
            return;
        }
        CrmEntity asset = findByKeyword(entityType, name);
        if (asset == null) {
            asset = createKeywordAsset(entityType, name, sender);
        } else if (!canAccess(asset, sender)) {
            return;
        }
        attach(message, sender, asset, config, true);
    }

    private CrmEntity findByKeyword(Class<?> type, String name) {
        if (type == Contact.class || type == Lead.class) {
            String[] parts = name.split("\\s+", 2);
            List<Long> ids = parts.length == 1
                ? jdbcTemplate.query("SELECT id FROM " + table(type) + " WHERE last_name LIKE ? ORDER BY id LIMIT 1",
                    (row, index) -> row.getLong(1), "%" + parts[0])
                : jdbcTemplate.query("SELECT id FROM " + table(type)
                    + " WHERE first_name LIKE ? AND last_name LIKE ? ORDER BY id LIMIT 1",
                    (row, index) -> row.getLong(1), "%" + parts[0], "%" + parts[1]);
            return ids.isEmpty() ? null : (CrmEntity) entityManager.find(type, ids.get(0));
        }
        List<Long> ids = jdbcTemplate.query("SELECT id FROM " + table(type)
                + " WHERE name LIKE ? ORDER BY id LIMIT 1",
            (row, index) -> row.getLong(1), "%" + name + "%");
        return ids.isEmpty() ? null : (CrmEntity) entityManager.find(type, ids.get(0));
    }

    private CrmEntity createKeywordAsset(Class<?> type, String name, User sender) {
        String access = defaultAccess();
        CrmEntity asset;
        if (type == Account.class) {
            Account account = new Account();
            account.setName(name);
            asset = account;
        } else if (type == Campaign.class) {
            Campaign campaign = new Campaign();
            campaign.setName(name);
            campaign.setStatus("planned");
            asset = campaign;
        } else if (type == Opportunity.class) {
            Opportunity opportunity = new Opportunity();
            opportunity.setName(name);
            opportunity.setStage(settings.opportunityDefaultStage());
            asset = opportunity;
        } else if (type == Contact.class || type == Lead.class) {
            String[] parts = name.split("\\s+", 2);
            if (type == Contact.class) {
                Contact contact = new Contact();
                contact.setFirstName(parts[0]);
                contact.setLastName(parts.length > 1 ? parts[1] : "(unknown)");
                asset = contact;
            } else {
                Lead lead = new Lead();
                lead.setFirstName(parts[0]);
                lead.setLastName(parts.length > 1 ? parts[1] : "(unknown)");
                lead.setStatus("contacted");
                asset = lead;
            }
        } else {
            return null;
        }
        asset.setUser(sender);
        asset.setAccess(access);
        Instant now = Instant.now();
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        entityManager.persist(asset);
        entityManager.flush();
        recordCreate(asset, sender);
        return asset;
    }

    private boolean findAndAttach(Message message, User sender, String address, Map<String, Object> config)
        throws Exception {
        boolean attached = false;
        for (Class<?> type : List.of(Account.class, Contact.class, Lead.class)) {
            String query = "SELECT id FROM " + table(type) + " WHERE lower(email) = lower(?)"
                + (type == Contact.class || type == Lead.class ? " OR lower(alt_email) = lower(?)" : "")
                + " ORDER BY id";
            List<Long> ids = type == Contact.class || type == Lead.class
                ? jdbcTemplate.query(query, (row, index) -> row.getLong(1), address, address)
                : jdbcTemplate.query(query, (row, index) -> row.getLong(1), address);
            for (Long id : ids) {
                CrmEntity asset = (CrmEntity) entityManager.find(type, id);
                if (canAccess(asset, sender)) {
                    attach(message, sender, asset, config, false);
                    attached = true;
                }
            }
        }
        return attached;
    }

    private void createAndAttach(Message message, User sender, String recipient, Map<String, Object> config)
        throws Exception {
        String[] address = recipient.split("@", 2);
        if (address.length != 2) {
            return;
        }
        String local = address[0];
        String domain = address[1];
        Contact contact = new Contact();
        contact.setUser(sender);
        contact.setFirstName(local.substring(0, 1).toUpperCase(Locale.ROOT) + local.substring(1));
        contact.setLastName("(unknown)");
        contact.setEmail(recipient);
        contact.setAccess(defaultAccess());
        Instant now = Instant.now();
        contact.setCreatedAt(now);
        contact.setUpdatedAt(now);
        List<Long> accountIds = jdbcTemplate.query(
            "SELECT id FROM accounts WHERE lower(email) LIKE ? OR lower(website) LIKE ? ORDER BY id LIMIT 1",
            (row, index) -> row.getLong(1), "%" + domain.toLowerCase(Locale.ROOT),
            "%" + domain.toLowerCase(Locale.ROOT) + "%");
        Account account;
        if (accountIds.isEmpty()) {
            account = new Account();
            account.setUser(sender);
            account.setEmail(recipient);
            account.setName(domain.substring(0, 1).toUpperCase(Locale.ROOT) + domain.substring(1));
            account.setAccess(defaultAccess());
            account.setCreatedAt(now);
            account.setUpdatedAt(now);
            entityManager.persist(account);
            entityManager.flush();
            recordCreate(account, sender);
        } else {
            account = entityManager.find(Account.class, accountIds.get(0));
        }
        entityManager.persist(contact);
        entityManager.flush();
        attachContactToAccount(contact, account, now, sender);
        recordCreate(contact, sender);
        attach(message, sender, contact, config, false);
    }

    private void attach(
        Message message,
        User sender,
        CrmEntity asset,
        Map<String, Object> config,
        boolean stripFirstLine
    ) throws Exception {
        String to = addresses(message.getRecipients(Message.RecipientType.TO));
        if (to.isBlank()) {
            to = string(config, "address");
        }
        String cc = addresses(message.getRecipients(Message.RecipientType.CC));
        String body = plainTextBody(message);
        if (stripFirstLine) {
            int newline = body.indexOf('\n');
            body = newline < 0 ? "" : body.substring(newline + 1).strip();
        }
        Date date = message.getSentDate();
        Instant sentAt = date == null ? null : date.toInstant();
        createEmail(message, sender, asset, to, cc, body, sentAt);
        Map<String, Object> beforeTouch = rowAttributes.read(asset);
        Instant now = Instant.now();
        asset.setUpdatedAt(now);
        entityManager.merge(asset);
        entityManager.flush();
        versionRecorder.recordTouch(new AuthenticatedUser(sender.getId(), null, false), asset, beforeTouch,
            rowAttributes.read(asset));
        if (asset instanceof Lead lead && "new".equals(lead.getStatus())) {
            Map<String, Object> beforeStatus = rowAttributes.read(lead);
            lead.setStatus("contacted");
            lead.setUpdatedAt(Instant.now());
            entityManager.merge(lead);
            entityManager.flush();
            versionRecorder.recordUpdate(new AuthenticatedUser(sender.getId(), null, false), lead, beforeStatus,
                rowAttributes.read(lead));
        }
        Integer accountId = relatedAccountId(asset);
        if (accountId != null) {
            Account account = entityManager.find(Account.class, accountId.longValue());
            createEmail(message, sender, account, to, cc, body, sentAt);
            Map<String, Object> beforeAccountTouch = rowAttributes.read(account);
            account.setUpdatedAt(now);
            entityManager.merge(account);
            entityManager.flush();
            versionRecorder.recordTouch(new AuthenticatedUser(sender.getId(), null, false), account,
                beforeAccountTouch, rowAttributes.read(account));
        }
    }

    private void createEmail(
        Message message, User sender, CrmEntity asset, String to, String cc, String body, Instant sentAt
    ) throws Exception {
        Email email = new Email();
        email.setUser(sender);
        email.setMediatorType(asset.getClass().getSimpleName());
        email.setMediatorId(Math.toIntExact(asset.getId()));
        String[] messageIds = message.getHeader("Message-ID");
        email.setImapMessageId(messageIds == null || messageIds.length == 0 ? "" : messageIds[0]);
        email.setSentFrom(address(message.getFrom()));
        email.setSentTo(to);
        email.setCc(cc.isBlank() ? null : cc);
        email.setSubject(message.getSubject());
        email.setBody(body);
        email.setReceivedAt(sentAt);
        email.setSentAt(sentAt);
        Instant now = Instant.now();
        email.setCreatedAt(now);
        email.setUpdatedAt(now);
        entityManager.persist(email);
        entityManager.flush();
        recordCreate(email, sender);
    }

    private Integer relatedAccountId(CrmEntity asset) {
        String table;
        String foreignKey;
        if (asset instanceof Contact) {
            table = "account_contacts";
            foreignKey = "contact_id";
        } else if (asset instanceof Opportunity) {
            table = "account_opportunities";
            foreignKey = "opportunity_id";
        } else {
            return null;
        }
        List<Integer> ids = jdbcTemplate.query("SELECT account_id FROM " + table + " WHERE " + foreignKey
                + " = ? ORDER BY id LIMIT 1",
            (row, index) -> row.getInt(1), Math.toIntExact(asset.getId()));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void attachContactToAccount(Contact contact, Account account, Instant now, User sender) {
        AccountContact association = new AccountContact();
        association.setAccount(account);
        association.setContact(contact);
        association.setCreatedAt(now);
        association.setUpdatedAt(now);
        entityManager.persist(association);
        entityManager.flush();
        recordCreate(association, sender);
    }

    private void recordCreate(Object entity, User sender) {
        versionRecorder.recordCreate(new AuthenticatedUser(sender.getId(), null, false), entity,
            rowAttributes.read(entity), rowAttributes.defaults(entity));
    }

    static List<String> explicitKeyword(String body) {
        String firstLine = body.split("\n", -1)[0];
        Matcher keyword = KEYWORD.matcher(firstLine);
        if (!keyword.find()) {
            return null;
        }
        String matchedKeyword = keyword.group(1);
        String capitalized = matchedKeyword.substring(0, 1).toUpperCase(Locale.ROOT)
            + matchedKeyword.substring(1).toLowerCase(Locale.ROOT);
        return List.of(capitalized, keyword.group(2).strip());
    }

    static List<String> recipients(Message message, Map<String, Object> config) throws Exception {
        List<String> recipients = new ArrayList<>();
        recipients.addAll(addressList(message.getRecipients(Message.RecipientType.TO)));
        recipients.addAll(addressList(message.getRecipients(Message.RecipientType.CC)));
        String dropbox = string(config, "address");
        List<?> aliases = config.get("address_aliases") instanceof List<?> list ? list : List.of();
        recipients.removeIf(address -> address.equals(dropbox)
            || aliases.stream().anyMatch(alias -> address.equals(String.valueOf(alias))));
        return recipients;
    }

    static String firstForwardedRecipient(String body) {
        Matcher matcher = FORWARDED_RECIPIENT.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String defaultAccess() {
        String access = settings.defaultAccess();
        return "Shared".equals(access) ? "Private" : access;
    }

    private static String table(Class<?> type) {
        if (type == Account.class) {
            return "accounts";
        }
        if (type == Campaign.class) {
            return "campaigns";
        }
        if (type == Contact.class) {
            return "contacts";
        }
        if (type == Lead.class) {
            return "leads";
        }
        if (type == Opportunity.class) {
            return "opportunities";
        }
        throw new IllegalArgumentException("Unsupported asset type " + type.getSimpleName());
    }
}
