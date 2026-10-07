package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Compares the two JPA mappings for a jsonb column:
 * {@link SpikeAccountConverter} (Jackson {@code AttributeConverter} to String +
 * {@code @ColumnTransformer(write = "?::jsonb")}) vs
 * {@link SpikeAccountJson} ({@code @JdbcTypeCode(SqlTypes.JSON)}).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonbMappingComparisonTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("spike_mapping")
        .withUsername("postgres")
        .withPassword("postgres");

    private EntityManagerFactory emf;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement st = conn.createStatement()) {
            st.execute("create table spike_accounts_converter ("
                + "id bigserial primary key, name varchar(64) not null,"
                + " custom_fields jsonb not null default '{}')");
            st.execute("create table spike_accounts_json ("
                + "id bigserial primary key, name varchar(64) not null,"
                + " custom_fields jsonb not null default '{}')");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        emf = SpikeJpa.createEmf(ds);
    }

    @AfterAll
    void tearDown() {
        if (emf != null) {
            emf.close();
        }
    }

    private static Map<String, Object> allKinds() {
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("cf_string", "hello");
        cf.put("cf_decimal", new BigDecimal("1234.50"));
        cf.put("cf_integer", 42);
        cf.put("cf_boolean", true);
        cf.put("cf_date", "2024-01-31");
        cf.put("cf_array", List.of("a", "b", "c"));
        cf.put("cf_null", null);
        return cf;
    }

    @Test
    void roundTripsEveryJsonValueKindWithConverter() {
        EntityManager em = emf.createEntityManager();
        SpikeAccountConverterRepository repo =
            new JpaRepositoryFactory(em).getRepository(SpikeAccountConverterRepository.class);

        SpikeAccountConverter saved = new SpikeAccountConverter();
        saved.setName("conv");
        saved.setCustomFields(allKinds());
        em.getTransaction().begin();
        saved = repo.save(saved);
        em.getTransaction().commit();
        Long id = saved.getId();

        em.clear();
        SpikeAccountConverter loaded = repo.findById(id).orElseThrow();
        Map<String, Object> cf = loaded.getCustomFields();

        assertThat(cf.get("cf_string")).isEqualTo("hello");
        // Jackson USE_BIG_DECIMAL_FOR_FLOATS: scale is preserved through the converter.
        assertThat(cf.get("cf_decimal")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) cf.get("cf_decimal")).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThat(cf.get("cf_integer")).isInstanceOf(Number.class);
        assertThat(((Number) cf.get("cf_integer")).longValue()).isEqualTo(42L);
        assertThat(cf.get("cf_boolean")).isEqualTo(true);
        assertThat(cf.get("cf_date")).isEqualTo("2024-01-31");
        assertThat(cf.get("cf_array")).isEqualTo(List.of("a", "b", "c"));
        // null JSON value survives as a key mapped to null; an absent key stays absent.
        assertThat(cf).containsKey("cf_null");
        assertThat(cf.get("cf_null")).isNull();
        assertThat(cf).doesNotContainKey("cf_absent");
        em.close();
    }

    @Test
    void roundTripsEveryJsonValueKindWithJdbcTypeCode() {
        EntityManager em = emf.createEntityManager();
        SpikeAccountJsonRepository repo =
            new JpaRepositoryFactory(em).getRepository(SpikeAccountJsonRepository.class);

        SpikeAccountJson saved = new SpikeAccountJson();
        saved.setName("json");
        saved.setCustomFields(allKinds());
        em.getTransaction().begin();
        saved = repo.save(saved);
        em.getTransaction().commit();
        Long id = saved.getId();

        em.clear();
        SpikeAccountJson loaded = repo.findById(id).orElseThrow();
        Map<String, Object> cf = loaded.getCustomFields();

        assertThat(cf.get("cf_string")).isEqualTo("hello");
        // @JdbcTypeCode uses the stock Jackson mapper: JSONB canonicalizes 1234.50 to
        // 1234.5 and it comes back as a Double (no USE_BIG_DECIMAL_FOR_FLOATS).
        assertThat(cf.get("cf_decimal")).isInstanceOf(Double.class);
        assertThat(((Number) cf.get("cf_decimal")).doubleValue()).isEqualTo(1234.5d);
        assertThat(((Number) cf.get("cf_integer")).longValue()).isEqualTo(42L);
        assertThat(cf.get("cf_boolean")).isEqualTo(true);
        assertThat(cf.get("cf_date")).isEqualTo("2024-01-31");
        assertThat(cf.get("cf_array")).isEqualTo(List.of("a", "b", "c"));
        assertThat(cf).containsKey("cf_null");
        assertThat(cf.get("cf_null")).isNull();
        assertThat(cf).doesNotContainKey("cf_absent");
        em.close();
    }

    /**
     * FINDING: mutating a key inside the loaded Map and flushing DOES issue an UPDATE
     * for BOTH variants. Hibernate snapshots the JSON content (@JdbcTypeCode snapshots
     * the deserialized structure; the AttributeConverter variant snapshots the
     * converted String), so in-place Map mutation is detected. The optimistic-locking
     * caveat stands regardless: concurrent cf_ column writes vs jsonb writes can still
     * clobber each other since the whole document is rewritten on UPDATE.
     */
    @Test
    void inPlaceMapMutationIsDirtyCheckedForBothVariants() {
        EntityManager em = emf.createEntityManager();
        SpikeAccountJsonRepository repo =
            new JpaRepositoryFactory(em).getRepository(SpikeAccountJsonRepository.class);

        SpikeAccountJson saved = new SpikeAccountJson();
        saved.setName("dirty-json");
        saved.setCustomFields(new LinkedHashMap<>(Map.of("cf_a", 1)));
        em.getTransaction().begin();
        saved = repo.save(saved);

        SqlRecorder.clear();
        saved.getCustomFields().put("cf_b", 2); // mutate in place, same Map instance
        em.flush();
        assertThat(SqlRecorder.statements())
            .as("OBSERVED: @JdbcTypeCode(JSON) DOES issue an UPDATE on in-place Map mutation"
                + " (the JSON type snapshots the serialized content, not the Map reference)")
            .anyMatch(s -> s.startsWith("update"));
        SqlRecorder.clear();

        // Replacing the map reference does trigger an update.
        Map<String, Object> replaced = new LinkedHashMap<>(saved.getCustomFields());
        replaced.put("cf_c", 3);
        saved.setCustomFields(replaced);
        SqlRecorder.clear();
        em.flush();
        assertThat(SqlRecorder.statements()).anyMatch(s -> s.startsWith("update"));
        em.getTransaction().commit();
        em.close();

        em = emf.createEntityManager();
        SpikeAccountConverterRepository convRepo =
            new JpaRepositoryFactory(em).getRepository(SpikeAccountConverterRepository.class);

        SpikeAccountConverter conv = new SpikeAccountConverter();
        conv.setName("dirty-conv");
        conv.setCustomFields(new LinkedHashMap<>(Map.of("cf_a", 1)));
        em.getTransaction().begin();
        conv = convRepo.save(conv);

        SqlRecorder.clear();
        conv.getCustomFields().put("cf_b", 2);
        em.flush();
        assertThat(SqlRecorder.statements())
            .as("OBSERVED: AttributeConverter variant ALSO issues an UPDATE on in-place"
                + " Map mutation (snapshot = converted String)")
            .anyMatch(s -> s.startsWith("update"));
        em.getTransaction().commit();
        em.close();
    }

    @Test
    void converterVariantBindsJsonbViaColumnTransformer() {
        EntityManager em = emf.createEntityManager();
        SpikeAccountConverterRepository repo =
            new JpaRepositoryFactory(em).getRepository(SpikeAccountConverterRepository.class);

        SpikeAccountConverter a = new SpikeAccountConverter();
        a.setName("sql-shape");
        a.setCustomFields(Map.of("cf_x", "v"));
        SqlRecorder.clear();
        em.getTransaction().begin();
        repo.save(a);
        em.flush();
        em.getTransaction().commit();

        // The write path must cast the varchar-bound String to jsonb, e.g. "custom_fields=?::jsonb".
        assertThat(SqlRecorder.statements())
            .filteredOn(s -> s.startsWith("insert"))
            .allMatch(s -> s.contains("?::jsonb") || s.contains("? ::jsonb")
                || s.contains("cast(? as jsonb)"));
        em.close();
    }
}
