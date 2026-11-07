package ru.itmo.energosbyt;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--generate")) {
            Dataset.generate(Path.of("data"));
            return;
        }
        SpringApplication.run(Application.class, args);
    }

    @Bean
    JdbcTemplate clickHouse(Environment environment) {
        String url = "jdbc:clickhouse:http://%s:%s/%s".formatted(
                environment.getRequiredProperty("CLICKHOUSE_HOST"),
                environment.getRequiredProperty("CLICKHOUSE_HTTP_PORT"),
                environment.getRequiredProperty("CLICKHOUSE_DATABASE"));
        var source = new DriverManagerDataSource(url,
                environment.getRequiredProperty("CLICKHOUSE_USER"),
                environment.getRequiredProperty("CLICKHOUSE_PASSWORD"));
        source.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        return new JdbcTemplate(source);
    }
}
