package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Row of the Rails {@code groups_users} join table (no primary key, {@code id: false}).
 * Mapped with a composite key so the access-control Specification can resolve group
 * membership with a Criteria subquery.
 */
@Entity
@Table(name = "groups_users")
public class GroupUser {

    @EmbeddedId
    private Key key;

    public Key getKey() {
        return key;
    }

    public void setKey(Key key) {
        this.key = key;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "group_id")
        private Long groupId;

        @Column(name = "user_id")
        private Long userId;

        public Long getGroupId() {
            return groupId;
        }

        public void setGroupId(Long groupId) {
            this.groupId = groupId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(groupId, other.groupId) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, userId);
        }
    }
}
