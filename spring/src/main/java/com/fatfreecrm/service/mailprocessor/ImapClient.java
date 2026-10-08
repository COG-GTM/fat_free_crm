package com.fatfreecrm.service.mailprocessor;

import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class ImapClient {

    public Store connect(Map<String, Object> config) throws Exception {
        boolean ssl = truthy(value(config, "ssl"));
        String protocol = ssl ? "imaps" : "imap";
        String server = value(config, "server");
        String port = value(config, "port");
        if (port.isBlank()) {
            port = ssl ? "993" : "143";
        }
        Properties properties = new Properties();
        properties.setProperty("mail." + protocol + ".port", port);
        Store store = Session.getInstance(properties).getStore(protocol);
        store.connect(server, Integer.parseInt(port), value(config, "user"), value(config, "password"));
        return store;
    }

    private static String value(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : value.toString();
    }

    private static boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
