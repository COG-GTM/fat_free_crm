package com.fatfreecrm.service.mail;

public record RenderedMail(String subject, String from, String to, String contentType, String body) {
}
