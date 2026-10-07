package com.fatfreecrm.domain.support;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.proxy.HibernateProxy;

@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that) || persistentClass(this) != persistentClass(that)) {
            return false;
        }
        Long thisId = identifier(this);
        return thisId != null && thisId.equals(identifier(that));
    }

    @Override
    public final int hashCode() {
        return persistentClass(this).hashCode();
    }

    private static Class<?> persistentClass(Object entity) {
        return entity instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : entity.getClass();
    }

    private static Long identifier(BaseEntity entity) {
        return entity instanceof HibernateProxy proxy
            ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
            : entity.id;
    }
}
