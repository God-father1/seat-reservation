package com.seatreserve;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.io.IOException;

public class TestPostgres {
    private static final EmbeddedPostgres pg;
    private static final DataSource dataSource;
    private static final String jdbcUrl;

    static {
        try {
            pg = EmbeddedPostgres.builder().start();
            dataSource = pg.getPostgresDatabase();
            jdbcUrl = pg.getJdbcUrl("postgres", "postgres");

            Flyway.configure()
                    .dataSource(dataSource)
                    .load()
                    .migrate();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start embedded Postgres", e);
        }
    }

    public static String getJdbcUrl() {
        return jdbcUrl;
    }

    public static DataSource getDataSource() {
        return dataSource;
    }
}
