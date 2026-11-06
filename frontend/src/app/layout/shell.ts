import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Auth } from '../core/auth';

@Component({
  selector: 'of-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip-link" href="#main">Pular para o conteúdo</a>
    <div class="shell" [class.shell--menu-open]="menuOpen()">
      <aside class="sidebar" aria-label="Navegação">
        <div class="brand">
          <span class="brand__mark" aria-hidden="true">OF</span>
          <span class="brand__name">OrderFlow</span>
        </div>
        <nav>
          <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
          <a routerLink="/orders" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Pedidos</a>
          <a routerLink="/orders/new" routerLinkActive="active">Novo pedido</a>
          <a routerLink="/products" routerLinkActive="active">Produtos</a>
          <a routerLink="/inventory" routerLinkActive="active">Estoque</a>
          @if (auth.isAdmin()) {
            <a routerLink="/processing" routerLinkActive="active">Processamento</a>
          }
        </nav>
        <p class="sidebar__note">Pagamentos simulados · dados de demonstração</p>
      </aside>

      <div class="content">
        <header class="topbar">
          <button type="button" class="btn btn--ghost menu-toggle" (click)="menuOpen.set(!menuOpen())"
                  [attr.aria-expanded]="menuOpen()" aria-label="Abrir menu">☰</button>
          <div class="topbar__user">
            <span class="topbar__name">{{ auth.user()?.fullName }}</span>
            <span class="role-pill">{{ auth.user()?.role === 'ADMIN' ? 'Administrador' : 'Operador' }}</span>
            <button type="button" class="btn btn--ghost" (click)="logout()">Sair</button>
          </div>
        </header>
        <main id="main" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class Shell {
  protected readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly menuOpen = signal(false);

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.menuOpen.set(false));
  }

  protected logout(): void {
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }
}
