package dev.caiorocha.orderflow.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login por sessão: o navegador recebe apenas o cookie de sessão HttpOnly (inacessível a JavaScript).
 * Nenhum token fica em localStorage. Requisições de escrita exigem o token CSRF (cookie XSRF-TOKEN +
 * header X-XSRF-TOKEN, enviado automaticamente pelo HttpClient do Angular).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final AppUserRepository users;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          SessionAuthenticationStrategy sessionStrategy, AppUserRepository users) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
        this.users = users;
    }

    public record LoginRequest(@NotBlank @Size(max = 160) String email, @NotBlank @Size(max = 100) String password) {
    }

    public record Me(String email, String fullName, String role) {
    }

    /** Força a emissão do cookie XSRF-TOKEN antes do primeiro POST. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                                   HttpServletResponse response) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.email().trim().toLowerCase(), req.password()));
            // Troca o id da sessão no login (proteção contra session fixation).
            sessionStrategy.onAuthentication(auth, request, response);
            SecurityContext context = holder.createEmptyContext();
            context.setAuthentication(auth);
            holder.setContext(context);
            contextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(me(auth.getName()));
        } catch (AuthenticationException e) {
            // Mensagem genérica: não revela se o e-mail existe. O e-mail não é registrado em log.
            log.info("Falha de login");
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos");
            pd.setProperty("code", "INVALID_CREDENTIALS");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
        }
    }

    @GetMapping("/me")
    public Me me(Authentication auth) {
        return me(auth.getName());
    }

    private Me me(String email) {
        AppUser user = users.findByEmail(email).orElseThrow();
        return new Me(user.getEmail(), user.getFullName(), user.getRole().name());
    }
}
