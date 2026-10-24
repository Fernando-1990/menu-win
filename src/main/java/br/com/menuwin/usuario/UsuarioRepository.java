package br.com.menuwin.usuario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Usuario não tem @TenantId: estas consultas NÃO são filtradas por loja. */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    /** E-mail é único no sistema todo e gravado em minúsculas. */
    Optional<Usuario> findByEmail(String email);
}
