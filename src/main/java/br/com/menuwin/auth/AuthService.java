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
