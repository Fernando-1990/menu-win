package br.com.menuwin.auth;

import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.tenant.TenantContext;
import br.com.menuwin.usuario.Usuario;
import br.com.menuwin.usuario.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final LojaRepository lojaRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /** Hash usado quando o e-mail não existe: o tempo de resposta não revela quais e-mails estão cadastrados. */
    private final String hashFalso;

    private final TentativasSenha tentativas = new TentativasSenha();

    public AuthService(UsuarioRepository usuarioRepository, LojaRepository lojaRepository,
                       PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.usuarioRepository = usuarioRepository;
        this.lojaRepository = lojaRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.hashFalso = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        Optional<Usuario> encontrado = usuarioRepository.findByEmail(req.email());
        boolean senhaConfere = passwordEncoder.matches(req.senha(), encontrado.map(Usuario::getSenhaHash).orElse(hashFalso));

        // Mesma resposta para e-mail inexistente, senha errada, usuário inativo ou loja inativa.
        Usuario usuario = encontrado
                .filter(u -> senhaConfere && u.isAtivo())
                .filter(u -> lojaRepository.findById(u.getLojaId()).map(Loja::isAtiva).orElse(false))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos"));

        return tokenService.emitir(usuario);
    }

    /**
     * Troca a própria senha. Exige a senha atual: quem pegou o painel aberto (ou o token) não fica com a conta.
     * Errar a atual 5 vezes em 15 min bloqueia novas tentativas por um tempo (429).
     * Tokens já emitidos continuam valendo até expirar (8h): não há revogação ainda.
     */
    @Transactional
    public void trocarSenha(UUID usuarioId, TrocarSenhaRequest req) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .filter(u -> u.getLojaId().equals(TenantContext.getObrigatorio()) && u.isAtivo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        tentativas.verificar(usuarioId);
        if (!passwordEncoder.matches(req.senhaAtual(), usuario.getSenhaHash())) {
            tentativas.registrarErro(usuarioId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Senha atual incorreta");
        }
        if (req.novaSenha().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha é longa demais. Use até 72 caracteres sem acento.");
        }
        if (req.novaSenha().isBlank() || req.novaSenha().strip().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ter pelo menos 8 caracteres");
        }
        if (passwordEncoder.matches(req.novaSenha(), usuario.getSenhaHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha precisa ser diferente da atual");
        }
        usuario.setSenhaHash(passwordEncoder.encode(req.novaSenha()));
        tentativas.limpar(usuarioId);
    }

    /** A loja vem do TenantContext, preenchido pelo PainelTenantFilter a partir do token. */
    @Transactional(readOnly = true)
    public SessaoResponse sessaoAtual(UUID usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Loja loja = lojaRepository.findById(TenantContext.getObrigatorio()).orElseThrow();
        return new SessaoResponse(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getPapel(),
                loja.getId(), loja.getNome(), loja.getSlug());
    }
}
