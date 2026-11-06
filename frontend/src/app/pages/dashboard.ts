import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { errorMessage } from '../core/errors';
import { Dashboard } from '../core/models';
import { Empty, ErrorAlert, Loading } from '../shared/state';
import { StatusBadge } from '../shared/status-badge';

@Component({
  selector: 'of-dashboard',
  imports: [RouterLink, CurrencyPipe, DatePipe, StatusBadge, Loading, ErrorAlert, Empty],
  template: `
    <div class="page-header">
      <div>
        <h1>Dashboard</h1>
        <p class="muted">Indicadores calculados no banco a cada carregamento.</p>
      </div>
      <button type="button" class="btn" (click)="load()" [disabled]="loading()">Atualizar</button>
    </div>

    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!data()) {
      <of-loading />
    } @else {
      @let d = data()!;
      <section class="kpis" aria-label="Indicadores">
        <div class="kpi"><span class="kpi__label">Pedidos totais</span><strong class="kpi__value" data-testid="kpi-total">{{ d.totalOrders }}</strong></div>
        <div class="kpi kpi--info"><span class="kpi__label">Pendentes</span><strong class="kpi__value" data-testid="kpi-pending">{{ d.pendingOrders }}</strong></div>
        <div class="kpi kpi--success"><span class="kpi__label">Aprovados</span><strong class="kpi__value" data-testid="kpi-paid">{{ d.paidOrders }}</strong></div>
        <div class="kpi kpi--danger"><span class="kpi__label">Recusados</span><strong class="kpi__value" data-testid="kpi-declined">{{ d.declinedOrders }}</strong></div>
        <div class="kpi kpi--danger"><span class="kpi__label">Falha no pagamento</span><strong class="kpi__value">{{ d.failedOrders }}</strong></div>
        <div class="kpi"><span class="kpi__label">Produtos cadastrados</span><strong class="kpi__value" data-testid="kpi-products">{{ d.totalProducts }}</strong><small>{{ d.activeProducts }} ativos</small></div>
      </section>

      <div class="grid-2">
        <section class="card">
          <div class="card__header">
            <h2>Pedidos recentes</h2>
            <a routerLink="/orders" class="link">Ver todos</a>
          </div>
          @if (d.recentOrders.length === 0) {
            <of-empty message="Nenhum pedido ainda. Crie o primeiro em “Novo pedido”." />
          } @else {
            <div class="table-wrap">
              <table class="table">
                <thead><tr><th>Pedido</th><th>Cliente</th><th>Status</th><th class="num">Total</th><th>Criado</th></tr></thead>
                <tbody>
                  @for (o of d.recentOrders; track o.id) {
                    <tr>
                      <td><a [routerLink]="['/orders', o.id]" class="link mono">{{ o.code }}</a></td>
                      <td>{{ o.customerName }}</td>
                      <td><of-status [status]="o.status" /></td>
                      <td class="num">{{ o.totalAmount | currency }}</td>
                      <td>{{ o.createdAt | date: 'dd/MM HH:mm' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>

        <section class="card">
          <div class="card__header">
            <h2>Alertas de estoque baixo</h2>
            <a routerLink="/inventory" class="link">Estoque</a>
          </div>
          @if (d.lowStock.length === 0) {
            <of-empty message="Nenhum produto ativo abaixo do limite." />
          } @else {
            <ul class="alert-list" data-testid="low-stock">
              @for (p of d.lowStock; track p.id) {
                <li>
                  <div><strong>{{ p.name }}</strong><span class="muted mono"> {{ p.sku }}</span></div>
                  <span class="badge badge--warning">{{ p.availableQuantity }} disp. · limite {{ p.lowStockThreshold }}</span>
                </li>
              }
            </ul>
          }
        </section>
      </div>
    }
  `,
})
export class DashboardPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly data = signal<Dashboard | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api.dashboard().subscribe({
      next: (d) => {
        this.data.set(d);
        this.error.set('');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }
}
