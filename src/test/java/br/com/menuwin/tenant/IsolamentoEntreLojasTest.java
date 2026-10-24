package br.com.menuwin.tenant;

import br.com.menuwin.cardapio.Categoria;
import br.com.menuwin.cardapio.CategoriaRepository;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sobe um PostgreSQL real (Docker), roda o Flyway e valida o mapeamento (ddl-auto=validate).
 * Se alguma entidade divergir do schema, o teste falha já na subida do contexto.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class IsolamentoEntreLojasTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void cadaLojaEnxergaSoAsProprias() {
        UUID lojaA = criarLoja("loja-a");
        UUID lojaB = criarLoja("loja-b");

        TenantContext.set(lojaA);
        categoriaRepository.save(categoria("Lanches da A"));

        TenantContext.set(lojaB);
        categoriaRepository.save(categoria("Lanches da B"));

        TenantContext.set(lojaA);
        assertThat(categoriaRepository.findByAtivaTrueOrderByOrdemAsc())
                .extracting(Categoria::getNome).containsExactly("Lanches da A");

        TenantContext.set(lojaB);
        assertThat(categoriaRepository.findAll())
                .extracting(Categoria::getNome).containsExactly("Lanches da B");

        TenantContext.clear();
        assertThat(categoriaRepository.findAll()).isEmpty();   // sem loja = não vê nada
    }

    private UUID criarLoja(String slug) {
        Loja loja = new Loja();
        loja.setSlug(slug);
        loja.setNome(slug);
        return lojaRepository.save(loja).getId();
    }

    private Categoria categoria(String nome) {
        Categoria c = new Categoria();   // lojaId NÃO é preenchido: o Hibernate preenche
        c.setNome(nome);
        return c;
    }
}
