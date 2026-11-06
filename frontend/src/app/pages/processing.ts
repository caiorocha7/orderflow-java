import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { errorMessage } from '../core/errors';
import { ProcessingOverview } from '../core/models';
import { Empty, ErrorAlert, Loading } from '../shared/state';

@Component({
  selector: 'of-processing',
  imports: [DatePipe, RouterLink, Loading, ErrorAlert, Empty],
  template: `
    <div class="page-header">
      <div>
        <h1>Histórico de processamento</h1>
        <p class="muted">Outbox, consumo no Kafka, retentativas e Dead Letter Topic. Visível apenas para administradores.</p>
      </div>
      <button type="button" class="btn" (click)="load()">Atualizar</button>
    </div>

    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!data()) {
      <of-loading />
    } @else {
      @let d = data()!;
      <section class="kpis" aria-label="Resumo do processamento">
        <div class="kpi"><span class="kpi__label">Eventos aguardando publicação</span><strong class="kpi__value">{{ d.pendingOutbox }}</strong></div>
        <div class="kpi kpi--success"><span class="kpi__label">Processados</span><strong class="kpi__value">{{ d.processed }}</strong></div>
        <div class="kpi kpi--info"><span class="kpi__label">Duplicados ignorados</span><strong class="kpi__value">{{ d.duplicatesIgnored }}</strong></div>
        <div class="kpi kpi--warning"><span class="kpi__label">Retentativas</span><strong class="kpi__value">{{ d.retries }}</strong></div>
        <div class="kpi kpi--danger"><span class="kpi__label">Enviados à DLT</span><strong class="kpi__value">{{ d.deadLettered }}</strong></div>
      </section>

      <div class="grid-2">
        <section class="card table-wrap">
          <h2>Consumo recente</h2>
          @if (d.recentConsumption.length === 0) {
            <of-empty message="Nenhum evento consumido ainda." />
          } @else {
            <table class="table table--compact">
              <thead><tr><th>Quando</th><th>Pedido</th><th>Resultado</th><th>Tent.</th><th>Detalhe</th></tr></thead>
              <tbody>
                @for (c of d.recentConsumption; track c.id) {
                  <tr>
                    <td class="mono">{{ c.createdAt | date: 'HH:mm:ss' }}</td>
                    <td>@if (c.orderId) { <a class="link" [routerLink]="['/orders', c.orderId]">#{{ c.orderId }}</a> }</td>
                    <td><span class="badge" [class]="'badge badge--' + tone(c.outcome)">{{ c.outcome }}</span></td>
                    <td class="num">{{ c.attempt }}</td>
                    <td class="muted">{{ c.detail }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
        <section class="card table-wrap">
          <h2>Outbox recente</h2>
          @if (d.recentEvents.length === 0) {
            <of-empty message="Nenhum evento gerado ainda." />
          } @else {
            <table class="table table--compact">
              <thead><tr><th>Criado</th><th>Evento</th><th>Pedido</th><th>Publicado</th></tr></thead>
              <tbody>
                @for (e of d.recentEvents; track e.id) {
                  <tr>
                    <td class="mono">{{ e.createdAt | date: 'HH:mm:ss' }}</td>
                    <td class="mono">{{ e.eventType }}</td>
                    <td><a class="link" [routerLink]="['/orders', e.aggregateId]">#{{ e.aggregateId }}</a></td>
                    <td>{{ e.publishedAt ? (e.publishedAt | date: 'HH:mm:ss') : (e.lastError ? 'erro: ' + e.lastError : 'pendente') }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
      </div>
    }
  `,
})
export class ProcessingPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly data = signal<ProcessingOverview | null>(null);
  protected readonly error = signal('');

  ngOnInit(): void {
    this.load();
  }

  protected tone(outcome: string): string {
    return { PROCESSED: 'success', DUPLICATE_IGNORED: 'info', SKIPPED: 'neutral', RETRYING: 'warning', DEAD_LETTERED: 'danger' }[outcome] ?? 'neutral';
  }

  protected load(): void {
    this.api.processing(40).subscribe({
      next: (d) => {
        this.data.set(d);
        this.error.set('');
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
