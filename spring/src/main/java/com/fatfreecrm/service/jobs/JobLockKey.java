package com.fatfreecrm.service.jobs;

public enum JobLockKey {
    DROPBOX_POLL(1),
    COMMENT_REPLIES_POLL(2),
    SOLID_QUEUE_DRAIN(3);

    private final int objectId;

    JobLockKey(int objectId) {
        this.objectId = objectId;
    }

    int objectId() {
        return objectId;
    }
}
