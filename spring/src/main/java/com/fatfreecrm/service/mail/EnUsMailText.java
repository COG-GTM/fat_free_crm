package com.fatfreecrm.service.mail;

import java.util.Map;
import java.util.Locale;
import java.util.ResourceBundle;
import org.springframework.stereotype.Service;

@Service
public class EnUsMailText implements MailText {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("mail.mail_text", Locale.US);

    @Override
    public String text(String key, Map<String, ?> args) {
        String value = TEXT.getString(key);
        for (Map.Entry<String, ?> argument : args.entrySet()) {
            value = value.replace("%{" + argument.getKey() + "}", String.valueOf(argument.getValue()));
        }
        return value;
    }
}
