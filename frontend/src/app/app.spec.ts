import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Auth } from './core/auth';
import { errorCode, errorMessage } from './core/errors';
import { StatusBadge } from './shared/status-badge';

describe('Auth', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('busca o cookie CSRF antes de enviar as credenciais e guarda o usuário só em memória', () => {
    let role = '';
    auth.login('admin@orderflow.local', 'segredo').subscribe((me) => (role = me.role));

    http.expectOne({ method: 'GET', url: '/api/auth/csrf' }).flush(null);
    const login = http.expectOne({ method: 'POST', url: '/api/auth/login' });
    expect(login.request.body).toEqual({ email: 'admin@orderflow.local', password: 'segredo' });
    login.flush({ email: 'admin@orderflow.local', fullName: 'Admin', role: 'ADMIN' });

    expect(role).toBe('ADMIN');
    expect(auth.isAdmin()).toBe(true);
    expect(localStorage.length).toBe(0);
  });

  it('sem sessão, load() devolve null e não é administrador', () => {
    let result: unknown = 'pendente';
    auth.load().subscribe((me) => (result = me));
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(result).toBeNull();
    expect(auth.isAdmin()).toBe(false);
  });
});

describe('errorMessage', () => {
  const problem = (status: number, body: object) => new HttpErrorResponse({ status, error: body });

  it('usa o detail do Problem Details', () => {
    expect(errorMessage(problem(422, { detail: 'Estoque insuficiente', code: 'INSUFFICIENT_STOCK' }))).toBe('Estoque insuficiente');
    expect(errorCode(problem(422, { code: 'INSUFFICIENT_STOCK' }))).toBe('INSUFFICIENT_STOCK');
  });

  it('lista os erros de validação por campo', () => {
    expect(errorMessage(problem(400, { errors: { price: 'não pode ser negativo' } }))).toBe('price: não pode ser negativo');
  });

  it('explica falta de conexão e usa a mensagem padrão para erros desconhecidos', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('Sem conexão');
    expect(errorMessage(new Error('x'), 'padrão')).toBe('padrão');
  });
});

describe('StatusBadge', () => {
  it('mostra o rótulo em português com a cor do status', async () => {
    const fixture = TestBed.createComponent(StatusBadge);
    fixture.componentRef.setInput('status', 'PAYMENT_DECLINED');
    await fixture.whenStable();
    const el = fixture.nativeElement.querySelector('.badge') as HTMLElement;
    expect(el.textContent).toBe('Pagamento recusado');
    expect(el.classList).toContain('badge--danger');
  });
});
