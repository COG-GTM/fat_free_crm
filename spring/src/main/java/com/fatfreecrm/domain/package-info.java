/**
 * JPA entities mirroring the Rails database schema, one class per table.
 *
 * <ul>
 *   <li>{@link com.fatfreecrm.domain.CrmEntity} is the {@code @MappedSuperclass} for the five core entities;
 *       there is no JPA inheritance anywhere in the model.</li>
 *   <li>Rails polymorphic associations are {@link com.fatfreecrm.domain.PolymorphicRef} embeddables over the
 *       existing {@code *_type}/{@code *_id} column pairs.</li>
 *   <li>Soft delete is {@code @SQLRestriction("deleted_at IS NULL")} on every table Rails soft-deletes
 *       (see {@link com.fatfreecrm.domain.SoftDeletable}).</li>
 *   <li>Rails-serialised columns ({@code subscribed_users}, {@code settings.value}, {@code preferences.value},
 *       {@code fields.collection}/{@code settings}, PaperTrail payloads) are kept byte-for-byte compatible.</li>
 * </ul>
 */
package com.fatfreecrm.domain;
