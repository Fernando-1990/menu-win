package br.com.menuwin.auth;

import java.time.Instant;

public record LoginResponse(String token, String tipo, Instant expiraEm) {}
