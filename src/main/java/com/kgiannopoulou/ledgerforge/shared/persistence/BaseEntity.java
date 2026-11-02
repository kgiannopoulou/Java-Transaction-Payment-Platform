package com.kgiannopoulou.ledgerforge.shared.persistence;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

/**
 * Base for entities whose UUID is assigned by the application (never by the database).
 *
 * <p>Without {@link Persistable}, Spring Data treats an entity with a non-null id as existing and
 * calls {@code merge()}: an extra SELECT per insert, and no PERSIST cascade. Tracking "newness"
 * explicitly makes {@code save()} issue a plain INSERT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean newEntity = true;

    protected BaseEntity() {
        // for JPA
    }

    protected BaseEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof BaseEntity that
                && getClass() == that.getClass()
                && id != null
                && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }
}
