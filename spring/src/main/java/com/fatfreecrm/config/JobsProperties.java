package com.fatfreecrm.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ffcrm.jobs")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Spring binds these mutable nested configuration properties."
)
public class JobsProperties {

    private String owner = "rails";
    private final Schedule dropbox = new Schedule();
    private final Schedule commentReplies = new Schedule();
    private final Drain solidQueueDrain = new Drain();
    private final Http http = new Http();
    private final Wikidata wikidata = new Wikidata();

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Schedule getDropbox() {
        return dropbox;
    }

    public Schedule getCommentReplies() {
        return commentReplies;
    }

    public Drain getSolidQueueDrain() {
        return solidQueueDrain;
    }

    public Http getHttp() {
        return http;
    }

    public Wikidata getWikidata() {
        return wikidata;
    }

    public static class Schedule {

        private String cron = "0 */5 * * * ?";
        private boolean dryRun;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }
    }

    public static class Drain {

        private String cron = "*/10 * * * * ?";
        private boolean enabled = true;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Http {

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
        private long maxBodyBytes = 5 * 1024 * 1024;
        private boolean blockPrivateAddresses;

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public long getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public boolean isBlockPrivateAddresses() {
            return blockPrivateAddresses;
        }

        public void setBlockPrivateAddresses(boolean blockPrivateAddresses) {
            this.blockPrivateAddresses = blockPrivateAddresses;
        }
    }

    public static class Wikidata {

        private String endpoint = "https://query.wikidata.org/sparql";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
