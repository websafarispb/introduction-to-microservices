package com.epam.learn.microservices.resourceservice.service;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every SQL statement Hibernate prepares, so tests can assert INSERT vs UPDATE.
 */
public class RecordingStatementInspector implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.toLowerCase());
        return sql;
    }

    static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }

    static void clear() {
        STATEMENTS.clear();
    }
}
