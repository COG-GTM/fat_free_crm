package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MailSettingsServiceTest {

    @Test
    void usesRailsLiteralsWhenNoSenderSettingsExist() {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        MailSettingsService service = new MailSettingsService(
            repository, "", "en-US", "Public", "", "", "");

        assertEquals("", service.smtpFrom());
        assertEquals("Casey Sender <noreply@fatfreecrm.com>", service.commentReplyFrom("Casey Sender"));
    }

    @Test
    void databaseHashReplacesDefaultsAndBlankReplyFallsBackToSmtp() {
        SettingRepository repository = mock(SettingRepository.class);
        Setting smtp = setting(Map.of("from", "smtp@example.test"));
        Setting commentReplies = setting(Map.of("address", ""));
        when(repository.findByName("smtp")).thenReturn(Optional.of(smtp));
        when(repository.findByName("email_comment_replies"))
            .thenReturn(Optional.of(commentReplies));
        MailSettingsService service = new MailSettingsService(
            repository, "", "en-US", "Public", "yaml-smtp@example.test", "yaml-reply@example.test", "");

        assertEquals(Map.of("address", ""), service.section("email_comment_replies"));
        assertEquals("Casey Sender <smtp@example.test>", service.commentReplyFrom("Casey Sender"));
    }

    @Test
    void preservesAnAlreadyNamedCommentReplyAddress() {
        SettingRepository repository = mock(SettingRepository.class);
        Setting smtp = setting(Map.of("from", "smtp@example.test"));
        Setting commentReplies = setting(Map.of("address", "Support Team <reply@example.test>"));
        when(repository.findByName("smtp")).thenReturn(Optional.of(smtp));
        when(repository.findByName("email_comment_replies"))
            .thenReturn(Optional.of(commentReplies));
        MailSettingsService service = new MailSettingsService(
            repository, "", "en-US", "Public", "", "", "");

        assertEquals("Support Team <reply@example.test>", service.commentReplyFrom("Casey Sender"));
    }

    @Test
    void usesSpringSettingsAsFallbackWhenDatabaseRowsAreAbsent() {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        MailSettingsService service = new MailSettingsService(
            repository, "", "en-US", "Public", "yaml-smtp@example.test", "", "");

        assertEquals(Map.of("from", "yaml-smtp@example.test"), service.section("smtp"));
        assertEquals("Casey Sender <yaml-smtp@example.test>", service.commentReplyFrom("Casey Sender"));
    }

    private static Setting setting(Map<String, Object> value) {
        Setting setting = mock(Setting.class);
        when(setting.getParsedValue()).thenReturn(value);
        return setting;
    }
}
