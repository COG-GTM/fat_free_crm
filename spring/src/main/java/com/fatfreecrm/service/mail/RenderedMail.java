package com.fatfreecrm.service.mail;

public record RenderedMail(String to, String from, String subject, String textBody, String htmlBody) {
}
