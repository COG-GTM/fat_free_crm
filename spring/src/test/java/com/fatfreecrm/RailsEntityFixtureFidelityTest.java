package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.service.PolymorphicReferenceService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RailsEntityFixtureFidelityTest {

    private static final List<String> SOFT_DELETE_TABLES = List.of(
        "accounts", "contacts", "leads", "opportunities", "campaigns", "tasks", "emails", "addresses",
        "account_contacts", "account_opportunities", "contact_opportunities"
    );

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_entity_fixture")
        .withUsername("postgres")
        .withPassword("postgres");

    static {
        POSTGRES.start();
        try {
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/rails_schema.sql"),
                "/tmp/rails_schema.sql"
            );
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/entity_fixture.sql"),
                "/tmp/entity_fixture.sql"
            );
            loadSql("/tmp/rails_schema.sql");
            loadSql("/tmp/entity_fixture.sql");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(exception);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PolymorphicReferenceService polymorphicReferenceService;

    @Test
    @Transactional
    void loadsEveryRailsRowAndMatchesEveryMappedColumn() throws ReflectiveOperationException {
        List<FixtureRow> rows = loadFixtureRows();
        for (FixtureRow row : rows) {
            assertMappedColumns(row);
        }
        Account invalidAccessAccount = accountRepository.findAll().stream()
            .filter(account -> account.getName().equals("Lead access fixture"))
            .findFirst()
            .orElseThrow();
        Lead invalidAccessLead = leadRepository.findAll().stream()
            .filter(lead -> lead.getFirstName().equals("Campaign access fixture"))
            .findFirst()
            .orElseThrow();
        assertThat(invalidAccessAccount.getAccess()).isEqualTo("Lead");
        assertThat(invalidAccessAccount.accessLevel()).isEmpty();
        assertThat(invalidAccessLead.getAccess()).isEqualTo("Campaign");
        assertThat(invalidAccessLead.accessLevel()).isEmpty();

        Map<String, List<String>> before = snapshots();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        for (FixtureRow row : rows) {
            saveAll(row);
        }
        FixtureRow mergeTarget = rows.stream()
            .filter(row -> row.table().equals("accounts"))
            .filter(row -> Boolean.FALSE.equals(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM public.accounts WHERE id = ?", Boolean.class,
                ((Account) row.value()).getId()
            )))
            .findFirst()
            .orElseThrow();
        entityManager.detach(mergeTarget.value());
        entityManager.merge(mergeTarget.value());
        entityManager.flush();
        assertThat(snapshots()).isEqualTo(before);
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityInsertCount()).isZero();
    }

    @Test
    @Transactional
    void resolvesKnownPolymorphicReferencesIncludingDeletedTargets() throws ReflectiveOperationException {
        for (PolymorphicPair pair : polymorphicPairs()) {
            List<Map<String, Object>> references = jdbcTemplate.queryForList(
                "SELECT " + columnName(pair.typeField()) + ", " + columnName(pair.idField())
                    + " FROM public." + pair.table()
            );
            for (Map<String, Object> reference : references) {
                Object typeValue = reference.get(columnName(pair.typeField()));
                Object idValue = reference.get(columnName(pair.idField()));
                if (typeValue == null || idValue == null) {
                    continue;
                }
                Optional<RailsModelType> type = RailsModelType.fromRailsName((String) typeValue);
                if (type.isEmpty()) {
                    continue;
                }
                Integer id = ((Number) idValue).intValue();
                assertThat(polymorphicReferenceService.resolve(type.orElseThrow(), id))
                    .as("known target %s %d", type.orElseThrow().railsName(), id)
                    .isPresent();
            }
        }
    }

    @Test
    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    void preservesUnknownPolymorphicTypesAcrossLoadsAndNoOpSaves() throws ReflectiveOperationException {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (PolymorphicPair pair : polymorphicPairs()) {
            entityManager.clear();
            statistics.clear();
            Long rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM public." + pair.table() + " ORDER BY id LIMIT 1", Long.class
            );
            jdbcTemplate.update(
                "UPDATE public." + pair.table() + " SET " + columnName(pair.typeField()) + " = ?, "
                    + columnName(pair.idField()) + " = COALESCE(" + columnName(pair.idField()) + ", 1) WHERE id = ?",
                "SomeUnknownModel",
                rowId
            );
            Integer polymorphicId = jdbcTemplate.queryForObject(
                "SELECT " + columnName(pair.idField()) + " FROM public." + pair.table() + " WHERE id = ?",
                Integer.class,
                rowId
            );
            String before = rowSnapshot(pair.table(), rowId);

            JpaRepository<?, Long> repository = (JpaRepository<?, Long>) repositoryFor(pair.entityClass());
            List<?> allRows = repository.findAll();
            Object fromAll = allRows.stream()
                .filter(row -> {
                    try {
                        return entityId(row).equals(rowId);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .findFirst()
                .orElseThrow();
            Object fromId = repository.findById(rowId).orElseThrow();
            String suffix = capitalize(pair.prefix());
            var rawTypeGetter = pair.entityClass().getMethod("get" + suffix + "Type");
            var modelTypeAccessor = pair.entityClass().getMethod(pair.prefix() + "ModelType");
            assertThat(rawTypeGetter.invoke(fromAll)).isEqualTo("SomeUnknownModel");
            assertThat(rawTypeGetter.invoke(fromId)).isEqualTo("SomeUnknownModel");
            assertThat(modelTypeAccessor.invoke(fromAll)).isEqualTo(Optional.empty());
            assertThat(polymorphicReferenceService.resolve("SomeUnknownModel", polymorphicId)).isEmpty();
            assertThat(polymorphicReferenceService.resolve((String) null, polymorphicId)).isEmpty();
            assertThat(polymorphicReferenceService.resolve("Account", null)).isEmpty();

            Object sample = pair.entityClass().getDeclaredConstructor().newInstance();
            pair.entityClass().getMethod("set" + suffix + "ModelType", RailsModelType.class)
                .invoke(sample, RailsModelType.ACCOUNT);
            assertThat(rawTypeGetter.invoke(sample)).isEqualTo("Account");
            pair.entityClass().getMethod("set" + suffix + "ModelType", RailsModelType.class)
                .invoke(sample, (Object) null);
            assertThat(rawTypeGetter.invoke(sample)).isNull();

            ((JpaRepository) repository).saveAll(List.of(fromId));
            entityManager.flush();
            assertThat(rowSnapshot(pair.table(), rowId)).isEqualTo(before);
            assertThat(statistics.getEntityUpdateCount()).isZero();
        }
    }

    @Test
    @Transactional
    void writesCopiedRailsRowsWithoutChangingColumnsExceptIdentity() throws ReflectiveOperationException {
        List<FixtureRow> rows = loadFixtureRows();
        for (FixtureRow row : rows) {
            Long originalId = entityId(row.value());
            Map<String, Object> original = jdbcTemplate.queryForMap(
                "SELECT * FROM public." + row.table() + " WHERE id = ?", originalId
            );
            entityManager.detach(row.value());
            jdbcTemplate.update("DELETE FROM public." + row.table() + " WHERE id = ?", originalId);
            Object copy = copyEntity(row.entityType().getJavaType(), row.value());
            entityManager.persist(copy);
            entityManager.flush();
            Long newId = entityId(copy);
            Map<String, Object> written = jdbcTemplate.queryForMap(
                "SELECT * FROM public." + row.table() + " WHERE id = ?", newId
            );
            original.remove("id");
            written.remove("id");
            if (original.get("subscribed_users") instanceof String serialized && serialized.equals("--- []\n")) {
                assertThat(written.get("subscribed_users")).isNull();
                original.put("subscribed_users", null);
            }
            assertThat(written).as("written values for %s row %d", row.table(), originalId).isEqualTo(original);
        }
    }

    @Test
    @Transactional
    void updatesTimestampWhenAnEntityChanges() {
        Account account = (Account) entityManager.createNativeQuery(
            "SELECT * FROM public.accounts WHERE deleted_at IS NULL ORDER BY id", Account.class
        ).setMaxResults(1).getSingleResult();
        Instant previous = account.getUpdatedAt();
        account.setBackgroundInfo("Spring update fixture");
        entityManager.flush();
        assertThat(account.getUpdatedAt()).isAfter(previous);
    }

    private List<FixtureRow> loadFixtureRows() throws ReflectiveOperationException {
        List<FixtureRow> result = new ArrayList<>();
        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> entityClass = entityType.getJavaType();
            Table tableAnnotation = entityClass.getAnnotation(Table.class);
            String table = tableAnnotation.name();
            Integer rowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM public." + table, Integer.class);
            assertThat(rowCount).as("fixture rows for %s", table).isPositive();
            if (SOFT_DELETE_TABLES.contains(table)) {
                Integer deletedCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM public." + table + " WHERE deleted_at IS NOT NULL", Integer.class
                );
                assertThat(deletedCount).as("deleted fixture rows for %s", table).isPositive();
            }
            Object repository = repositoryFor(entityClass);
            List<?> values = ((JpaRepository<?, ?>) repository).findAll();
            assertThat(values).as("loaded rows for %s", table).hasSize(rowCount);
            for (Object value : values) {
                result.add(new FixtureRow(entityType, table, value));
            }
        }
        Integer memberships = jdbcTemplate.queryForObject("SELECT count(*) FROM public.groups_users", Integer.class);
        assertThat(memberships).isPositive();
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void saveAll(FixtureRow row) {
        ((JpaRepository) repositoryFor(row.entityType().getJavaType())).saveAll(List.of(row.value()));
    }

    private Object repositoryFor(Class<?> entityClass) {
        return new Repositories(applicationContext).getRepositoryFor(entityClass)
            .orElseThrow(() -> new IllegalStateException("No Spring Data repository for " + entityClass.getName()));
    }

    private void assertMappedColumns(FixtureRow row) throws ReflectiveOperationException {
        Object entity = Hibernate.unproxy(row.value());
        Long entityId = entityId(entity);
        Map<String, Object> database = jdbcTemplate.queryForMap(
            "SELECT * FROM public." + row.table() + " WHERE id = ?", entityId
        );
        for (Attribute<?, ?> attribute : row.entityType().getAttributes()) {
            if (!(attribute.getJavaMember() instanceof Field field) || isCollection(attribute)) {
                continue;
            }
            field.setAccessible(true);
            String column = columnName(field);
            Object value = field.get(entity);
            if (field.isAnnotationPresent(ManyToOne.class)) {
                value = associationId(value);
            } else {
                value = convertedValue(field, value);
            }
            Object databaseValue = database.get(column);
            if ("subscribed_users".equals(column) && "--- []\n".equals(databaseValue)) {
                assertThat(value).isNull();
                continue;
            }
            assertThat(jdbcComparable(value, databaseValue))
                .as("%s.%s.%s", row.table(), entityId, column)
                .isEqualTo(databaseValue);
        }
    }

    private boolean isCollection(Attribute<?, ?> attribute) {
        return attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_MANY
            || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_MANY
            || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ELEMENT_COLLECTION;
    }

    private String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null) {
            return unquote(column.name().isBlank() ? field.getName() : column.name());
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn != null) {
            return unquote(joinColumn.name());
        }
        return field.getName().equals("id") ? "id" : camelToSnake(field.getName());
    }

    private Object associationId(Object association) throws ReflectiveOperationException {
        if (association == null) {
            return null;
        }
        Long id = entityId(association);
        return id.intValue();
    }

    private Long entityId(Object entity) throws ReflectiveOperationException {
        if (entity instanceof HibernateProxy proxy) {
            return ((Number) proxy.getHibernateLazyInitializer().getIdentifier()).longValue();
        }
        return ((Number) entity.getClass().getMethod("getId").invoke(entity)).longValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object convertedValue(Field field, Object value) throws ReflectiveOperationException {
        Convert convert = field.getAnnotation(Convert.class);
        if (convert == null || value == null) {
            return value;
        }
        Constructor<?> constructor = convert.converter().getDeclaredConstructor();
        constructor.setAccessible(true);
        AttributeConverter converter = (AttributeConverter) constructor.newInstance();
        return converter.convertToDatabaseColumn(value);
    }

    private Object jdbcComparable(Object value, Object databaseValue) {
        if (value instanceof Instant instant) {
            return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        }
        if (value instanceof Long longValue && databaseValue instanceof Integer) {
            return longValue.intValue();
        }
        if (value instanceof BigDecimal decimal && databaseValue instanceof BigDecimal stored) {
            return decimal.stripTrailingZeros().compareTo(stored.stripTrailingZeros()) == 0 ? stored : decimal;
        }
        return value;
    }

    private Object copyEntity(Class<?> type, Object source) throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object copy = constructor.newInstance();
        Object unproxied = Hibernate.unproxy(source);
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals("id")
                    || java.util.Collection.class.isAssignableFrom(field.getType())
                        && !field.isAnnotationPresent(Convert.class)) {
                    continue;
                }
                field.setAccessible(true);
                field.set(copy, field.get(unproxied));
            }
        }
        return copy;
    }

    private Map<String, List<String>> snapshots() {
        Map<String, List<String>> result = new HashMap<>();
        entityManagerFactory.getMetamodel().getEntities().forEach(entity -> {
            String table = entity.getJavaType().getAnnotation(Table.class).name();
            result.put(table, jdbcTemplate.queryForList(
                "SELECT row_to_json(t)::text FROM public." + table + " t ORDER BY id",
                String.class
            ));
        });
        result.put("groups_users", jdbcTemplate.queryForList(
            "SELECT row_to_json(t)::text FROM public.groups_users t ORDER BY group_id, user_id", String.class
        ));
        return result;
    }

    private String rowSnapshot(String table, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT row_to_json(t)::text FROM public." + table + " t WHERE id = ?", String.class, id
        );
    }

    private List<PolymorphicPair> polymorphicPairs() {
        List<PolymorphicPair> result = new ArrayList<>();
        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> entityClass = entityType.getJavaType();
            String table = entityClass.getAnnotation(Table.class).name();
            for (Field typeField : entityClass.getDeclaredFields()) {
                if (!typeField.getName().endsWith("Type") || typeField.getType() != String.class) {
                    continue;
                }
                String prefix = typeField.getName().substring(0, typeField.getName().length() - "Type".length());
                try {
                    Field idField = entityClass.getDeclaredField(prefix + "Id");
                    if (idField.getType() == Integer.class) {
                        result.add(new PolymorphicPair(table, entityClass, prefix, typeField, idField));
                    }
                } catch (NoSuchFieldException ignored) {
                    // Non-polymorphic fields named *Type have no matching *Id.
                }
            }
        }
        assertThat(result).hasSize(11);
        return result;
    }

    private String unquote(String identifier) {
        return identifier.replace("\"", "").replace("`", "");
    }

    private String camelToSnake(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record FixtureRow(EntityType<?> entityType, String table, Object value) {
    }

    private record PolymorphicPair(
        String table,
        Class<?> entityClass,
        String prefix,
        Field typeField,
        Field idField
    ) {
    }

    private static void loadSql(String path) throws IOException, InterruptedException {
        var load = POSTGRES.execInContainer(
            "psql", "-U", "postgres", "-d", "fat_free_crm_entity_fixture",
            "-v", "ON_ERROR_STOP=1", "-f", path
        );
        if (load.getExitCode() != 0) {
            throw new IOException("Loading " + path + " failed: " + load.getStderr());
        }
    }
}
