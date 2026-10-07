package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * A Rails polymorphic association ({@code belongs_to :x, polymorphic: true}) persisted exactly as Rails does:
 * a {@code *_type} column holding the Rails class name and a {@code *_id} column holding the row id.
 *
 * <p>Deliberately not a JPA inheritance hierarchy or {@code @Any} association: the two columns stay plain so Rails
 * and Spring can read each other's rows during the strangler window. Resolve the target through the matching
 * repository (for example {@code accountRepository.findById(ref.getId())} when {@code ref.getType()} is
 * {@code "Account"}).</p>
 */
@Embeddable
public class PolymorphicRef {

    @Column(name = "ref_type")
    private String type;

    @Column(name = "ref_id", columnDefinition = "int4")
    private Long id;

    protected PolymorphicRef() {
    }

    private PolymorphicRef(String type, Long id) {
        this.type = type;
        this.id = id;
    }

    public static PolymorphicRef of(String type, Long id) {
        return new PolymorphicRef(Objects.requireNonNull(type, "type"), Objects.requireNonNull(id, "id"));
    }

    public static PolymorphicRef to(User user) {
        return of(User.RAILS_TYPE, user.getId());
    }

    public static PolymorphicRef to(CrmEntity entity) {
        return entity.toRef();
    }

    public static PolymorphicRef to(Task task) {
        return of(Task.RAILS_TYPE, task.getId());
    }

    public String getType() {
        return type;
    }

    public Long getId() {
        return id;
    }

    public boolean isA(String railsType) {
        return railsType.equals(type);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PolymorphicRef that)) {
            return false;
        }
        return Objects.equals(type, that.type) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        return type + "#" + id;
    }
}
