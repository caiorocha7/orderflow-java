import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { errorMessage } from '../core/errors';
import { ORDER_STATUS_LABEL, OrderStatus, OrderSummary, PAYMENT_SCENARIO_LABEL, Page } from '../core/models';
import { Empty, ErrorAlert, Loading } from '../shared/state';
import { StatusBadge } from '../shared/status-badge';

@Component({
  selector: 'of-orders',
  imports: [RouterLink, FormsModule, CurrencyPipe, DatePipe, StatusBadge, Loading, ErrorAlert, Empty],
  template: `
    <div class="page-header">
      <div>
        <h1>Pedidos</h1>
        <p class="muted">O pagamento é processado de forma assíncrona via Kafka; o status muda sozinho.</p>
      </div>
      <a routerLink="/orders/new" class="btn btn--primary">Novo pedido</a>
    </div>

    <div class="filters">
      <label class="field field--inline">
        <span>Status</span>
        <select [(ngModel)]="status" (change)="load(0)" name="status" data-testid="status-filter">
          <option value="">Todos</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ statusLabel[s] }}</option>
          }
        </select>
      </label>
      <button class="btn" type="button" (click)="load(page()?.page ?? 0)">Atualizar</button>
    </div>

    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!page()) {
      <of-loading />
    } @else if (page()!.content.length === 0) {
      <of-empty message="Nenhum pedido com este filtro." />
    } @else {
      <div class="card table-wrap">
        <table class="table">
          <thead><tr><th>Pedido</th><th>Cliente</th><th>Status</th><th>Cenário de pagamento</th><th class="num">Itens</th><th class="num">Total</th><th>Criado por</th><th>Data</th></tr></thead>
          <tbody>
            @for (o of page()!.content; track o.id) {
              <tr>
                <td><a [routerLink]="['/orders', o.id]" class="link mono">{{ o.code }}</a></td>
                <td>{{ o.customerName }}</td>
                <td><of-status [status]="o.status" /></td>
                <td class="muted">{{ scenarioLabel[o.paymentScenario] }}</td>
                <td class="num">{{ o.itemCount }}</td>
                <td class="num">{{ o.totalAmount | currency }}</td>
                <td class="muted">{{ o.createdBy }}</td>
                <td>{{ o.createdAt | date: 'dd/MM/yyyy HH:mm' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <nav class="pager" aria-label="Paginação">
        <button class="btn" type="button" [disabled]="page()!.page === 0" (click)="load(page()!.page - 1)">Anterior</button>
        <span>Página {{ page()!.page + 1 }} de {{ page()!.totalPages || 1 }} · {{ page()!.totalElements }} pedidos</span>
        <button class="btn" type="button" [disabled]="page()!.page + 1 >= page()!.totalPages" (click)="load(page()!.page + 1)">Próxima</button>
      </nav>
    }
  `,
})
export class OrdersPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly page = signal<Page<OrderSummary> | null>(null);
  protected readonly error = signal('');
  protected readonly statuses = Object.keys(ORDER_STATUS_LABEL) as OrderStatus[];
  protected readonly statusLabel = ORDER_STATUS_LABEL;
  protected readonly scenarioLabel = PAYMENT_SCENARIO_LABEL;
  protected status: OrderStatus | '' = '';

  ngOnInit(): void {
    this.load(0);
  }

  protected load(pageNumber: number): void {
    this.api.orders({ status: this.status, page: pageNumber }).subscribe({
      next: (p) => {
        this.page.set(p);
        this.error.set('');
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
