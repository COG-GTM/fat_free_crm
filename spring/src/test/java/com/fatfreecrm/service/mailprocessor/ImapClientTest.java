package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Store;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImapClientTest {

    @Test
    void connectsToAnImapMailbox() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP));
        greenMail.start();
        Store store = null;
        try {
            greenMail.setUser("user@localhost", "user", "secret");
            store = new ImapClient().connect(Map.of(
                "ssl", false,
                "server", "127.0.0.1",
                "port", Integer.toString(greenMail.getImap().getPort()),
                "user", "user",
                "password", "secret"));
            assertTrue(store.isConnected());
            assertTrue(store.getFolder("INBOX").exists());
        } finally {
            if (store != null && store.isConnected()) {
                store.close();
            }
            greenMail.stop();
        }
    }
}
