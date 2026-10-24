package br.com.menuwin.cliente;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ClienteRepository extends JpaRepository<Cliente, UUID> {

    /**
     * Cria o cliente ou atualiza nome/e-mail se o telefone já existe na loja. Atômico: dois pedidos
     * simultâneos do mesmo cliente novo (toque duplo no botão) não estouram a UNIQUE (loja_id, telefone).
     * Query nativa: NÃO passa pelo @TenantId, por isso loja_id vai explícito.
     */
    @Query(value = """
            INSERT INTO clientes (loja_id, nome, telefone, email) VALUES (:lojaId, :nome, :telefone, :email)
            ON CONFLICT (loja_id, telefone)
            DO UPDATE SET nome = EXCLUDED.nome, email = COALESCE(EXCLUDED.email, clientes.email)
            RETURNING id""", nativeQuery = true)
    UUID salvarPorTelefone(UUID lojaId, String nome, String telefone, String email);
}
