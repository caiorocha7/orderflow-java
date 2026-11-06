import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { Auth } from '../core/auth';
import { errorMessage } from '../core/errors';
import { Product, StockMovement } from '../core/models';
import { Empty, ErrorAlert, Loading } from '../shared/state';

const MOVEMENT_LABEL: Record<StockMovement['type'], string> = {
  INITIAL: 'Entrada inicial',
  ADJUSTMENT: 'Ajuste manual',
  RESERVE: 'Reserva',
  RELEASE: 'Reserva liberada',
  CONSUME: 'Venda confirmada',
  RETURN: 'Devolução',
};

@Component({
  selector: 'of-inventory',
  imports: [FormsModule, DatePipe, Loading, ErrorAlert, Empty],
  template: `
    <div class="page-header">
      <div>
        <h1>Estoque</h1>
        <p class="muted"><strong>Disponível</strong> pode ser vendido; <strong>reservado</strong> aguarda pagamento. Venda confirmada sai do saldo; recusa ou cancelamento devolve ao disponível.</p>
      </div>
    </div>

    @if (flash()) { <div class="alert alert--success" role="status">{{ flash() }}</div> }
    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!products()) {
      <of-loading />
    } @else {
      <div class="grid-2 grid-2--wide-left">
        <div class="card table-wrap">
          <table class="table">
            <thead><tr><th>Produto</th><th class="num">Disponível</th><th class="num">Reservado</th><th class="num">Limite</th><th><span class="sr-only">Ações</span></th></tr></thead>
            <tbody>
              @for (p of products(); track p.id) {
                <tr [class.row--selected]="selected()?.id === p.id">
                  <td><strong>{{ p.name }}</strong><div class="muted mono">{{ p.sku }}</div></td>
                  <td class="num">{{ p.availableQuantity }} @if (p.lowStock) { <span class="badge badge--warning">baixo</span> }</td>
                  <td class="num">{{ p.reservedQuantity }}</td>
                  <td class="num">{{ p.lowStockThreshold }}</td>
                  <td class="actions"><button type="button" class="btn btn--small" (click)="select(p)">Movimentações</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <aside class="card">
          @if (!selected()) {
            <of-empty message="Selecione um produto para ver as movimentações e ajustar o saldo." />
          } @else {
            @let p = selected()!;
            <h2>{{ p.name }}</h2>
            <p class="muted mono">{{ p.sku }} · disponível {{ p.availableQuantity }} · reservado {{ p.reservedQuantity }}</p>

            @if (auth.isAdmin()) {
              <form class="adjust" (ngSubmit)="adjust(p)">
                <h3>Ajustar disponível</h3>
                <div class="adjust__row">
                  <label class="field field--inline"><span>Novo saldo</span><input type="number" min="0" step="1" name="qty" [(ngModel)]="newQty" required /></label>
                  <label class="field field--inline field--grow"><span>Motivo</span><input name="reason" [(ngModel)]="reason" maxlength="200" placeholder="Ex.: contagem de inventário" required /></label>
                </div>
                @if (adjustError()) { <small class="field__error">{{ adjustError() }}</small> }
                <button class="btn btn--primary btn--small" type="submit" [disabled]="adjusting()">Aplicar ajuste</button>
              </form>
            }

            <h3>Últimas movimentações</h3>
            @if (!movements()) {
              <of-loading />
            } @else if (movements()!.length === 0) {
              <of-empty message="Sem movimentações." />
            } @else {
              <ul class="timeline">
                @for (m of movements(); track m.id) {
                  <li>
                    <div class="timeline__title">{{ label(m.type) }} <span class="mono" [class.text-danger]="m.quantity < 0">{{ m.quantity > 0 && m.type === 'ADJUSTMENT' ? '+' : '' }}{{ m.quantity }}</span></div>
                    <div class="muted">{{ m.reason }} · {{ m.createdBy }} · {{ m.createdAt | date: 'dd/MM/yyyy HH:mm:ss' }}</div>
                  </li>
                }
              </ul>
            }
          }
        </aside>
      </div>
    }
  `,
})
export class InventoryPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly products = signal<Product[] | null>(null);
  protected readonly selected = signal<Product | null>(null);
  protected readonly movements = signal<StockMovement[] | null>(null);
  protected readonly error = signal('');
  protected readonly adjustError = signal('');
  protected readonly adjusting = signal(false);
  protected readonly flash = signal('');
  protected newQty = 0;
  protected reason = '';

  ngOnInit(): void {
    this.reload();
  }

  protected label(type: StockMovement['type']): string {
    return MOVEMENT_LABEL[type];
  }

  private reload(selectId?: number): void {
    this.api.products({ size: 100 }).subscribe({
      next: (page) => {
        this.products.set(page.content);
        const sel = page.content.find((p) => p.id === selectId);
        if (sel) this.select(sel);
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected select(p: Product): void {
    this.selected.set(p);
    this.newQty = p.availableQuantity;
    this.reason = '';
    this.adjustError.set('');
    this.movements.set(null);
    this.api.stockMovements(p.id).subscribe({ next: (m) => this.movements.set(m), error: () => this.movements.set([]) });
  }

  protected adjust(p: Product): void {
    if (this.newQty == null || this.newQty < 0 || !Number.isInteger(Number(this.newQty)) || !this.reason.trim()) {
      this.adjustError.set('Informe um saldo inteiro maior ou igual a zero e o motivo.');
      return;
    }
    this.adjusting.set(true);
    this.api.adjustStock(p.id, Number(this.newQty), this.reason.trim()).subscribe({
      next: () => {
        this.adjusting.set(false);
        this.flash.set(`Saldo de ${p.name} ajustado.`);
        this.reload(p.id);
      },
      error: (err) => {
        this.adjusting.set(false);
        this.adjustError.set(errorMessage(err));
      },
    });
  }
}
