package com.fatfreecrm.service.mail;

import java.util.Map;

public interface MailText {

    String text(String key, Map<String, ?> args);
}
