package com.loanrun.common.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnonymousUserService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnonymousUserService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public long resolve(String sessionId) {
        String digest = digest(sessionId);
        jdbc.update("""
            INSERT INTO demo_users(session_digest, name, created_at, updated_at)
            VALUES (?, '사용자', ?, ?) ON CONFLICT (session_digest) DO NOTHING
            """, digest, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        return jdbc.queryForObject("SELECT id FROM demo_users WHERE session_digest = ?", Long.class, digest);
    }

    private String digest(String sessionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
