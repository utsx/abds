package ru.itmo.energosbyt;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class EnergosbytApi {
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final LocalDate FIRST_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate LAST_READING_DATE = FIRST_DATE.plusDays(65_535);
    private static final LocalDate LAST_TARIFF_DATE = LocalDate.of(2106, 2, 7);
    private final JdbcTemplate db;

    EnergosbytApi(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/ping")
    ResponseEntity<Void> ping() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users/{id}")
    Map<String, Object> user(@PathVariable String id) {
        return one("SELECT id, fio, phone, phone_prev FROM dim_users FINAL WHERE id = toUInt64(?)",
                objectId(id));
    }

    @PatchMapping("/users/{id}")
    synchronized ResponseEntity<Void> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body) {
        id = objectId(id);
        if (!body.containsKey("fio") && !body.containsKey("phone")) {
            throw invalid("fio", "field");
        }
        String fio = optionalText(body, "fio");
        String phone = optionalText(body, "phone");
        var current = one("""
                SELECT fio, phone, phone_prev, toUnixTimestamp(created_at) AS created,
                       toUnixTimestamp(updated_at) AS version
                FROM dim_users FINAL WHERE id = toUInt64(?)
                """, id);
        String oldPhone = (String) current.get("phone");
        String previous = phone != null && !phone.equals(oldPhone)
                ? oldPhone : (String) current.get("phone_prev");
        db.update("""
                INSERT INTO dim_users
                SELECT toUInt64(?), ?, ?, ?, toDateTime(?), toDateTime(?)
                """, id, fio == null ? current.get("fio") : fio, phone == null ? oldPhone : phone, previous,
                ((Number) current.get("created")).longValue(),
                nextTimestamp(((Number) current.get("version")).longValue()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/realty/{id}")
    Map<String, Object> realty(@PathVariable String id) {
        return one("""
                SELECT id, address, square, type, number, rooms, floor, porch
                FROM dim_realty WHERE id = toUInt64(?)
                """, objectId(id));
    }

    @PostMapping("/realty/{id}/readings")
    synchronized ResponseEntity<Void> addReading(@PathVariable String id, @RequestBody Map<String, Object> body) {
        id = objectId(id);
        String user = idField(body, "user_id");
        String gauge = idField(body, "gauge_id");
        String tariff = idField(body, "tariff_id");
        LocalDate date = date(body.get("reading_date"), "reading_date", "field");
        if (date.isBefore(FIRST_DATE) || date.isAfter(LAST_READING_DATE)) {
            throw invalid("reading_date", "field");
        }
        long day = unsigned32(body, "day_kw");
        long night = unsigned32(body, "night_kw");
        requireRealty(id);
        one("SELECT id FROM dim_users FINAL WHERE id = toUInt64(?)", user);
        requireGauge(gauge);
        one("SELECT id FROM dim_tariffs FINAL WHERE id = toUInt64(?) LIMIT 1", tariff);
        Long version = db.queryForObject("""
                SELECT max(toUnixTimestamp(created_at))
                FROM fact_meter_readings WHERE realty_id = toUInt64(?) AND reading_date = toDate(?)
                """, Long.class, id, date.toString());
        db.update("""
                INSERT INTO fact_meter_readings
                SELECT toUInt64(?), toUInt64(?), toUInt64(?), toUInt64(?), toDate(?),
                       toUInt32(?), toUInt32(?), toDateTime(?)
                """, id, user, tariff, gauge, date.toString(), day, night, nextTimestamp(version));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/realty/{id}/readings")
    Map<String, Object> readings(@PathVariable String id,
                                 @RequestParam(name = "date_from", required = false) String from,
                                 @RequestParam(name = "date_to", required = false) String to) {
        id = objectId(id);
        LocalDate start = date(from, "date_from", "parameter");
        LocalDate end = date(to, "date_to", "parameter");
        if (start.isBefore(FIRST_DATE) || start.isAfter(end) || start.isAfter(LAST_READING_DATE)) {
            throw invalid("date_from", "parameter");
        }
        if (end.isAfter(LAST_READING_DATE)) {
            throw invalid("date_to", "parameter");
        }
        requireRealty(id);
        var rows = db.queryForList("""
                SELECT toString(reading_date) AS reading_date, user_id, tariff_id, gauge_id, day_kw, night_kw
                FROM fact_meter_readings FINAL
                WHERE realty_id = toUInt64(?)
                  AND fact_meter_readings.reading_date BETWEEN toDate(?) AND toDate(?)
                ORDER BY reading_date
                """, id, start.toString(), end.toString());
        return Map.of("readings", rows, "count", rows.size());
    }

    @GetMapping("/realty/{id}/invoices")
    Map<String, Object> invoices(@PathVariable String id,
                                 @RequestParam(defaultValue = "10") String limit,
                                 @RequestParam(defaultValue = "0") String offset) {
        id = objectId(id);
        long pageSize = pageParameter(limit, "limit");
        long skip = pageParameter(offset, "offset");
        requireRealty(id);
        Long count = db.queryForObject("SELECT count() FROM fact_invoice WHERE realty_id = toUInt64(?)",
                Long.class, id);
        List<Map<String, Object>> rows = pageSize == 0 || skip >= count ? List.of() : db.queryForList("""
                SELECT tariff_id, gauge_id, day_kw, night_kw, price, toString(deadline) AS deadline,
                       toString(created_at) AS created_at
                FROM fact_invoice WHERE realty_id = toUInt64(?)
                ORDER BY fact_invoice.created_at DESC LIMIT ? OFFSET ?
                """, id, Math.min(pageSize, count - skip), skip);
        return Map.of("invoices", rows, "count", count);
    }

    @GetMapping("/tariffs")
    Map<String, Object> tariff(@RequestParam(name = "date", required = false) String value) {
        LocalDate requested = date(value, "date", "parameter");
        if (requested.isBefore(FIRST_DATE) || requested.isAfter(LAST_TARIFF_DATE)) {
            throw new ApiError(HttpStatus.NOT_FOUND, "Not found");
        }
        String day = requested + " 00:00:00";
        return one("""
                SELECT id, name, price_day_kwph, price_night_kwph, toString(valid_from) AS valid_from,
                       toString(valid_to) AS valid_to
                FROM dim_tariffs FINAL
                WHERE dim_tariffs.valid_from <= toDateTime(?) AND dim_tariffs.valid_to > toDateTime(?)
                ORDER BY dim_tariffs.valid_from DESC, id LIMIT 1
                """, day, day);
    }

    @PostMapping("/realty/{id}/gauges")
    synchronized ResponseEntity<Void> installGauge(@PathVariable String id, @RequestBody Map<String, Object> body) {
        id = objectId(id);
        String gauge = idField(body, "gauge_id");
        requireRealty(id);
        requireGauge(gauge);
        var active = db.queryForList("""
                SELECT gauge_id, version FROM fact_gauge_bindings FINAL
                WHERE realty_id = toUInt64(?) AND sign = 1 ORDER BY version DESC LIMIT 1
                """, id);
        if (active.isEmpty()) {
            db.update("""
                    INSERT INTO fact_gauge_bindings
                    SELECT toUInt64(?), toUInt64(?), now(), toInt8(1), toUInt64(1)
                    """, gauge, id);
        } else {
            var previous = active.getFirst();
            String version = previous.get("version").toString();
            db.update("""
                    INSERT INTO fact_gauge_bindings
                    SELECT toUInt64(?), toUInt64(?), now(), toInt8(-1), toUInt64(?)
                    UNION ALL
                    SELECT toUInt64(?), toUInt64(?), now(), toInt8(1), toUInt64(?) + 1
                    """, previous.get("gauge_id").toString(), id, version, gauge, id, version);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, String>> apiError(ApiError error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> invalidBody() {
        return apiError(invalid("body", "field"));
    }

    private void requireRealty(String id) {
        one("SELECT id FROM dim_realty WHERE id = toUInt64(?)", id);
    }

    private void requireGauge(String id) {
        one("SELECT id FROM dim_gauges WHERE id = toUInt64(?)", id);
    }

    private Map<String, Object> one(String query, Object... args) {
        var rows = db.queryForList(query, args);
        if (rows.isEmpty()) {
            throw new ApiError(HttpStatus.NOT_FOUND, "Not found");
        }
        return rows.getFirst();
    }

    private static String optionalText(Map<String, Object> body, String name) {
        if (!body.containsKey(name)) {
            return null;
        }
        if (body.get(name) instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(name, "field");
    }

    private static String objectId(String value) {
        if (value.matches("[0-9]{1,20}") && new BigInteger(value).compareTo(UINT64_MAX) <= 0) {
            return value;
        }
        throw new ApiError(HttpStatus.NOT_FOUND, "Not found");
    }

    private static String idField(Map<String, Object> body, String name) {
        BigInteger value = integral(body.get(name), name);
        if (value.signum() < 0 || value.compareTo(UINT64_MAX) > 0) {
            throw invalid(name, "field");
        }
        return value.toString();
    }

    private static long unsigned32(Map<String, Object> body, String name) {
        BigInteger value = integral(body.get(name), name);
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(UINT32_MAX)) > 0) {
            throw invalid(name, "field");
        }
        return value.longValueExact();
    }

    private static BigInteger integral(Object value, String name) {
        if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            return new BigInteger(value.toString());
        }
        throw invalid(name, "field");
    }

    private static LocalDate date(Object value, String name, String location) {
        if (value instanceof String text && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException ignored) {
                throw invalid(name, location);
            }
        }
        throw invalid(name, location);
    }

    private static long pageParameter(String value, String name) {
        try {
            if (value.matches("[0-9]+")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            throw invalid(name, "parameter");
        }
        throw invalid(name, "parameter");
    }

    private static long nextTimestamp(Long previous) {
        return Math.max(Instant.now().getEpochSecond(), previous == null ? 0 : previous + 1);
    }

    private static ApiError invalid(String name, String location) {
        return new ApiError(HttpStatus.BAD_REQUEST, "invalid \"%s\" %s".formatted(name, location));
    }

    private static final class ApiError extends RuntimeException {
        private final HttpStatus status;

        private ApiError(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
