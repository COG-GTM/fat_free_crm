package com.fatfreecrm.spike.customfields;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records every SQL statement Hibernate prepares. Registered via
 * {@code hibernate.session_factory.statement_inspector}.
 */
public class SqlRecorder implements StatementInspector {

    private static final long serialVersionUID = 1L;

    private static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }

    public static String last() {
        List<String> all = statements();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
