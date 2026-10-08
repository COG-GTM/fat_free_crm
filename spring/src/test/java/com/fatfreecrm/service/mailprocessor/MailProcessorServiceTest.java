package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;

class MailProcessorServiceTest {

    @Test
    void onlyTheExactHtmlContentTypeIsInvalid() throws Exception {
        Message bareHtml = mock(Message.class);
        when(bareHtml.getContentType()).thenReturn("text/html");
        Message htmlWithCharset = mock(Message.class);
        when(htmlWithCharset.getContentType()).thenReturn("text/html; charset=UTF-8");
        Message upperCaseHtml = mock(Message.class);
        when(upperCaseHtml.getContentType()).thenReturn("TEXT/HTML");

        assertFalse(MailProcessorService.isValid(bareHtml));
        assertTrue(MailProcessorService.isValid(htmlWithCharset));
        assertTrue(MailProcessorService.isValid(upperCaseHtml));
    }
}
