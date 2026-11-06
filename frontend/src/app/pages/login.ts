import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../core/auth';
import { errorMessage } from '../core/errors';

@Component({
  selector: 'of-login',
  imports: [ReactiveFormsModule],
  template: `
    <div class="login">
      <form class="card login__card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="brand brand--dark">
          <span class="brand__mark" aria-hidden="true">OF</span>
          <span class="brand__name">OrderFlow</span>
        </div>
        <h1>Entrar</h1>
        <p class="muted">Gestão de pedidos e estoque com processamento assíncrono.</p>

        @if (error()) {
          <div class="alert alert--danger" role="alert">{{ error() }}</div>
        }

        <label class="field">
          <span>E-mail</span>
          <input type="email" formControlName="email" autocomplete="username" [attr.aria-invalid]="invalid('email')" />
          @if (invalid('email')) {
            <small class="field__error">Informe um e-mail válido.</small>
          }
        </label>
        <label class="field">
          <span>Senha</span>
          <input type="password" formControlName="password" autocomplete="current-password" [attr.aria-invalid]="invalid('password')" />
          @if (invalid('password')) {
            <small class="field__error">Informe a senha.</small>
          }
        </label>
        <button class="btn btn--primary btn--block" type="submit" [disabled]="loading()">
          {{ loading() ? 'Entrando...' : 'Entrar' }}
        </button>
        <p class="hint">Ambiente de demonstração: as credenciais estão no README (variáveis do <code>.env</code>).</p>
      </form>
    </div>
  `,
})
export class LoginPage {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  protected invalid(name: 'email' | 'password'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || c.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => this.router.navigateByUrl(this.route.snapshot.queryParamMap.get('returnUrl') || '/dashboard'),
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Não foi possível entrar.'));
      },
    });
  }
}
