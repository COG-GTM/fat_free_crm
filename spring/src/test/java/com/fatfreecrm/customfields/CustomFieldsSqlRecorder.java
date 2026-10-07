package com.fatfreecrm.customfields;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public class CustomFieldsSqlRecorder implements StatementInspector {

    private static final long serialVersionUID = 1L;
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
