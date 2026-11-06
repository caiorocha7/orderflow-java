import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, interval, switchMap, takeWhile } from 'rxjs';
import { Api } from '../core/api';
import { errorMessage } from '../core/errors';
import { ORDER_STATUS_LABEL, OrderDetail, OrderProcessing, PAYMENT_SCENARIO_LABEL } from '../core/models';
import { ErrorAlert, Loading } from '../shared/state';
import { StatusBadge } from '../shared/status-badge';

const OUTCOME_LABEL: Record<string, string> = {
  PROCESSED: 'Processado',
  DUPLICATE_IGNORED: 'Duplicado ignorado',
  SKIPPED: 'Ignorado (pedido não pendente)',
  RETRYING: 'Falha temporária · nova tentativa',
  DEAD_LETTERED: 'Enviado à DLT · compensado',
};

@Component({
  selector: 'of-order-detail',
  imports: [RouterLink, FormsModule, CurrencyPipe, DatePipe, StatusBadge, Loading, ErrorAlert],
  template: `
    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!order()) {
      <of-loading />
    } @else {
      @let o = order()!;
      <div class="page-header">
        <div>
          <h1><span class="mono">{{ o.code }}</span> <of-status [status]="o.status" data-testid="order-status" /></h1>
          <p class="muted">{{ o.customerName }} · {{ o.customerEmail }} · criado por {{ o.createdBy }} em {{ o.createdAt | date: 'dd/MM/yyyy HH:mm:ss' }}</p>
        </div>
        <a routerLink="/orders" class="btn btn--ghost">Voltar</a>
      </div>

      @if (o.status === 'PENDING_PAYMENT') {
        <div class="alert alert--info" role="status">Pagamento simulado em processamento via Kafka. Esta tela atualiza sozinha.</div>
      }
      @if (flash()) { <div class="alert alert--success" role="status">{{ flash() }}</div> }

      <div class="grid-2 grid-2--wide-left">
        <section class="card">
          <h2>Itens</h2>
          <div class="table-wrap">
            <table class="table">
              <thead><tr><th>Produto</th><th class="num">Preço na compra</th><th class="num">Qtd.</th><th class="num">Subtotal</th></tr></thead>
              <tbody>
                @for (i of o.items; track i.productId) {
                  <tr><td>{{ i.productName }} <span class="muted mono">{{ i.sku }}</span></td><td class="num">{{ i.unitPrice | currency }}</td><td class="num">{{ i.quantity }}</td><td class="num">{{ i.lineTotal | currency }}</td></tr>
                }
              </tbody>
              <tfoot><tr><th colspan="3">Total (calculado no servidor)</th><th class="num" data-testid="order-total">{{ o.totalAmount | currency }}</th></tr></tfoot>
            </table>
          </div>
          <p class="muted">Cenário de pagamento: {{ scenarioLabel[o.paymentScenario] }}</p>

          @if (o.cancellable) {
            <form class="cancel" (ngSubmit)="cancel()">
              <h3>Cancelar pedido</h3>
              <p class="muted">{{ o.status === 'PAID' ? 'Pedido pago: o estorno é simulado e os itens voltam ao estoque.' : 'A reserva de estoque será liberada.' }}</p>
              <div class="adjust__row">
                <label class="field field--inline field--grow"><span>Motivo (opcional)</span><input name="reason" [(ngModel)]="reason" maxlength="200" /></label>
                <button type="submit" class="btn btn--danger" [disabled]="cancelling()" data-testid="cancel-order">Cancelar pedido</button>
              </div>
              @if (cancelError()) { <small class="field__error">{{ cancelError() }}</small> }
            </form>
          }
        </section>

        <section class="card">
          <h2>Histórico de status</h2>
          <ol class="timeline" data-testid="history">
            @for (h of o.history; track $index) {
              <li>
                <div class="timeline__title">{{ statusLabel[h.toStatus] }}</div>
                <div class="muted">{{ h.reason }} · {{ h.changedBy }} · {{ h.changedAt | date: 'dd/MM HH:mm:ss.SSS' }}</div>
              </li>
            }
          </ol>
        </section>
      </div>

      <section class="card">
        <h2>Processamento assíncrono</h2>
        @if (processing(); as p) {
          <div class="grid-3">
            <div>
              <h3>Eventos (outbox → Kafka)</h3>
              <ul class="timeline">
                @for (e of p.events; track e.id) {
                  <li><div class="timeline__title mono">{{ e.eventType }}</div><div class="muted">{{ e.publishedAt ? 'publicado ' + (e.publishedAt | date: 'HH:mm:ss.SSS') : 'aguardando publicação' }}</div></li>
                }
              </ul>
            </div>
            <div>
              <h3>Consumo (Kafka)</h3>
              <ul class="timeline" data-testid="consumption">
                @for (c of p.consumption; track c.id) {
                  <li><div class="timeline__title">{{ outcomeLabel[c.outcome] }} <span class="muted">· tentativa {{ c.attempt }}</span></div><div class="muted">{{ c.detail }} · {{ c.createdAt | date: 'HH:mm:ss.SSS' }}</div></li>
                } @empty {
                  <li class="muted">Ainda não consumido.</li>
                }
              </ul>
            </div>
            <div>
              <h3>Tentativas no gateway simulado</h3>
              <ul class="timeline">
                @for (a of p.paymentAttempts; track a.attempt) {
                  <li><div class="timeline__title">#{{ a.attempt }} {{ a.outcome }}</div><div class="muted">{{ a.simulatedReference ?? '—' }} · {{ a.createdAt | date: 'HH:mm:ss.SSS' }}</div></li>
                } @empty {
                  <li class="muted">Nenhuma tentativa ainda.</li>
                }
              </ul>
            </div>
          </div>
        } @else {
          <of-loading />
        }
      </section>
    }
  `,
})
export class OrderDetailPage implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly order = signal<OrderDetail | null>(null);
  protected readonly processing = signal<OrderProcessing | null>(null);
  protected readonly error = signal('');
  protected readonly cancelError = signal('');
  protected readonly cancelling = signal(false);
  protected readonly flash = signal('');
  protected readonly statusLabel = ORDER_STATUS_LABEL;
  protected readonly scenarioLabel = PAYMENT_SCENARIO_LABEL;
  protected readonly outcomeLabel = OUTCOME_LABEL;
  protected reason = '';

  ngOnInit(): void {
    this.refresh();
    // Enquanto o pagamento está pendente, consulta a cada 1,5 s; para quando o status fica estável.
    interval(1500)
      .pipe(
        takeWhile(() => !this.order() || this.order()!.status === 'PENDING_PAYMENT' || this.pendingPublication()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.refresh());
  }

  private pendingPublication(): boolean {
    return (this.processing()?.events ?? []).some((e) => !e.publishedAt);
  }

  private refresh(): void {
    const id = Number(this.id());
    forkJoin({ order: this.api.order(id), processing: this.api.orderProcessing(id) }).subscribe({
      next: ({ order, processing }) => {
        this.order.set(order);
        this.processing.set(processing);
      },
      error: (err) => this.error.set(errorMessage(err, 'Pedido não encontrado.')),
    });
  }

  protected cancel(): void {
    this.cancelling.set(true);
    this.cancelError.set('');
    this.api
      .cancelOrder(Number(this.id()), this.reason)
      .pipe(switchMap(() => this.api.orderProcessing(Number(this.id()))))
      .subscribe({
        next: (processing) => {
          this.cancelling.set(false);
          this.processing.set(processing);
          this.flash.set('Pedido cancelado.');
          this.refresh();
        },
        error: (err) => {
          this.cancelling.set(false);
          this.cancelError.set(errorMessage(err));
        },
      });
  }
}
