package ru.itmo.energosbyt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

final class Dataset {
    private static final int OBJECTS = 1_000;
    private static final LocalDate START = LocalDate.of(2023, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 1);
    private static final LocalDate PRICE_CHANGE = LocalDate.of(2026, 7, 1);
    private static final LocalDate REPLACEMENT = LocalDate.of(2025, 5, 1);

    static void generate(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var realty = new Csv(directory, "dim_realty");
             var gauges = new Csv(directory, "dim_gauges");
             var tariffs = new Csv(directory, "dim_tariffs");
             var users = new Csv(directory, "dim_users");
             var bindings = new Csv(directory, "fact_gauge_bindings");
             var readings = new Csv(directory, "fact_meter_readings");
             var invoices = new Csv(directory, "fact_invoice")) {
            String initial = START + " 00:00:00";
            String change = PRICE_CHANGE + " 00:00:00";
            tariffs.row(1, "День–Ночь", 500, 250, initial, change, initial, change);
            tariffs.row(1, "День–Ночь", 620, 310, change, "2099-01-01 00:00:00", change, change);

            for (int id = 1; id <= OBJECTS; id++) {
                boolean flat = id % 10 != 0;
                realty.row(id, "ул. Энергетиков, д. " + (1 + (id - 1) / 50),
                        35 + id % 60, flat ? "flat" : "nrcp", flat ? id : "",
                        flat ? 1 + id % 4 : 0, 1 + id % 9, 1 + id % 3, initial);
                users.row(id, "Собственник " + id, "+7900000%04d".formatted(id), "", initial, initial);
                gauges.row(id, "Энергомер " + id % 5, "Россия", initial);
                bindings.row(id, id, initial, 1, 1);
                boolean replaced = id % 10 == 0;
                int newGauge = OBJECTS + id / 10;
                if (replaced) {
                    String installed = REPLACEMENT + " 00:00:00";
                    gauges.row(newGauge, "Энергомер 6", "Россия", installed);
                    bindings.row(id, id, installed, -1, 1);
                    bindings.row(newGauge, id, installed, 1, 2);
                }

                int day = 1_000;
                int night = 500;
                for (LocalDate month = START; month.isBefore(END); month = month.plusMonths(1)) {
                    if (replaced && month.equals(REPLACEMENT)) {
                        day = 0;
                        night = 0;
                    }
                    int gauge = replaced && !month.isBefore(REPLACEMENT) ? newGauge : id;
                    int monthDay = 0;
                    int monthNight = 0;
                    LocalDate nextMonth = month.plusMonths(1);
                    for (LocalDate date = month; date.isBefore(nextMonth); date = date.plusDays(1)) {
                        int dailyDay = 2 + (id + date.getDayOfYear()) % 10;
                        int dailyNight = 1 + (id + date.getDayOfMonth()) % 5;
                        day += dailyDay;
                        night += dailyNight;
                        monthDay += dailyDay;
                        monthNight += dailyNight;

                        boolean correction = (id + date.toEpochDay()) % 100 < 3;
                        readings.row(id, id, 1, gauge, date, correction ? day - 1 : day,
                                night, date + " 18:00:00");
                        if (correction) {
                            readings.row(id, id, 1, gauge, date, day, night, date + " 19:00:00");
                        }
                    }
                    boolean oldPrice = month.isBefore(PRICE_CHANGE);
                    long price = (long) monthDay * (oldPrice ? 500 : 620)
                            + (long) monthNight * (oldPrice ? 250 : 310);
                    invoices.row(id, id, 1, gauge, monthDay, monthNight, price,
                            nextMonth.withDayOfMonth(10), nextMonth.minusDays(1) + " 23:00:00");
                }
            }
        }
    }

    private static final class Csv implements AutoCloseable {
        private final BufferedWriter writer;

        Csv(Path directory, String table) throws IOException {
            writer = Files.newBufferedWriter(directory.resolve(table + ".csv"));
        }

        void row(Object... fields) throws IOException {
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) {
                    writer.write(',');
                }
                if (fields[i] instanceof String value) {
                    writer.write('"');
                    writer.write(value.replace("\"", "\"\""));
                    writer.write('"');
                } else {
                    writer.write(fields[i].toString());
                }
            }
            writer.newLine();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    private Dataset() {
    }
}
