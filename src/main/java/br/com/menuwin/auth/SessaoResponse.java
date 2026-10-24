package br.com.menuwin.auth;

import br.com.menuwin.usuario.Papel;

import java.util.UUID;

/** Quem está logado no painel e em qual loja. */
public record SessaoResponse(UUID usuarioId, String nome, String email, Papel papel,
                             UUID lojaId, String lojaNome, String lojaSlug) {}
