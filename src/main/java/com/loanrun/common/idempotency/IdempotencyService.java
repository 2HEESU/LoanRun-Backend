package com.loanrun.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.UserMutationLock;
import com.loanrun.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    public record StoredResponse(int status, JsonNode body) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserMutationLock locks;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper, UserMutationLock locks, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.locks = locks; this.clock = clock;
    }

    /** Mutation callback, revision bump and response are committed atomically; never call external services here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse execute(long userId, String method, String path, String key, JsonNode request,
            String conflictCode, Supplier<StoredResponse> mutation) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.validation("Idempotency-Key", "1~128자의 요청 키가 필요합니다.");
        }
        locks.lock(userId);
        String hash = hash(canonical(request).toString());
        var existing = jdbc.query("""
            SELECT body_hash, response_status, response_body FROM idempotency_records
            WHERE user_id=? AND http_method=? AND request_path=? AND request_key=?
            """, (rs, i) -> new StoredRecord(rs.getString(1), rs.getInt(2), rs.getString(3)), userId, method, path, key);
        if (!existing.isEmpty()) {
            var record = existing.getFirst();
            if (!record.hash().equals(hash)) throw new ApiException(HttpStatus.CONFLICT, conflictCode, "같은 요청 키에 다른 입력값을 사용할 수 없습니다.");
            try { return new StoredResponse(record.status(), mapper.readTree(record.body())); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Invalid persisted response"); }
        }
        StoredResponse result = mutation.get();
        jdbc.update("""
            INSERT INTO idempotency_records(user_id,http_method,request_path,request_key,body_hash,response_status,response_body,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, userId, method, path, key, hash, result.status(), result.body().toString(), java.sql.Timestamp.from(clock.instant()));
        return result;
    }

    private record StoredRecord(String hash, int status, String body) {}

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return mapper.nullNode();
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(node.get(key))));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }

    private String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
