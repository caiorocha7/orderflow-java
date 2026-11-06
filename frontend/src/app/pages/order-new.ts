import { CurrencyPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { errorMessage } from '../core/errors';
import { PAYMENT_SCENARIO_LABEL, PaymentScenario, Product } from '../core/models';
import { Loading } from '../shared/state';

@Component({
  selector: 'of-order-new',
  imports: [ReactiveFormsModule, RouterLink, CurrencyPipe, Loading],
  template: `
    <div class="page-header">
      <div>
        <h1>Novo pedido</h1>
        <p class="muted">O total exibido é só uma prévia: preços e total oficiais são calculados no servidor.</p>
      </div>
      <a routerLink="/orders" class="btn btn--ghost">Voltar</a>
    </div>

    @if (!products()) {
      <of-loading label="Carregando produtos..." />
    } @else {
      <form class="card form" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        @if (error()) { <div class="alert alert--danger" role="alert" data-testid="order-error">{{ error() }}</div> }

        <div class="form-grid">
          <label class="field">
            <span>Cliente</span>
            <input formControlName="customerName" [attr.aria-invalid]="bad('customerName')" />
            @if (bad('customerName')) { <small class="field__error">Informe o nome do cliente.</small> }
          </label>
          <label class="field">
            <span>E-mail do cliente</span>
            <input type="email" formControlName="customerEmail" [attr.aria-invalid]="bad('customerEmail')" />
            @if (bad('customerEmail')) { <small class="field__error">Informe um e-mail válido.</small> }
          </label>
          <label class="field field--wide">
            <span>Cenário do pagamento <em class="muted">(simulado, sem cobrança real)</em></span>
            <select formControlName="paymentScenario" data-testid="scenario">
              @for (s of scenarios; track s) { <option [value]="s">{{ scenarioLabel[s] }}</option> }
            </select>
          </label>
        </div>

        <h2 class="section-title">Itens</h2>
        <div formArrayName="items" class="items">
          @for (item of items.controls; track item; let i = $index) {
            <div class="item-row" [formGroupName]="i">
              <label class="field field--grow">
                <span>Produto</span>
                <select formControlName="productId" [attr.data-testid]="'item-product-' + i">
                  <option [ngValue]="null" disabled>Selecione</option>
                  @for (p of products(); track p.id) {
                    <option [ngValue]="p.id" [disabled]="p.availableQuantity === 0">{{ p.name }} · {{ p.price | currency }} · {{ p.availableQuantity }} disp.</option>
                  }
                </select>
              </label>
              <label class="field field--qty">
                <span>Qtd.</span>
                <input type="number" min="1" step="1" formControlName="quantity" [attr.data-testid]="'item-qty-' + i" />
              </label>
              <button type="button" class="btn btn--ghost" (click)="remove(i)" [disabled]="items.length === 1" [attr.aria-label]="'Remover item ' + (i + 1)">Remover</button>
            </div>
          }
        </div>
        @if (itemsInvalid()) { <small class="field__error">Cada item precisa de um produto (sem repetir) e quantidade inteira maior que zero.</small> }
        <button type="button" class="btn btn--small" (click)="add()">Adicionar item</button>

        <div class="summary">
          <span class="muted">Prévia do total</span>
          <strong>{{ preview() | currency }}</strong>
        </div>
        <div class="form-actions">
          <button class="btn btn--primary" type="submit" [disabled]="saving()" data-testid="submit-order">{{ saving() ? 'Criando...' : 'Criar pedido' }}</button>
        </div>
      </form>
    }
  `,
})
export class OrderNewPage implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly products = signal<Product[] | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly itemsInvalid = signal(false);
  protected readonly scenarios = Object.keys(PAYMENT_SCENARIO_LABEL) as PaymentScenario[];
  protected readonly scenarioLabel = PAYMENT_SCENARIO_LABEL;
  /** Uma chave por tentativa de criação: reenvios (duplo clique, retry) não duplicam o pedido. */
  private idempotencyKey = crypto.randomUUID();

  protected readonly form = this.fb.group({
    customerName: ['', [Validators.required, Validators.maxLength(120)]],
    customerEmail: ['', [Validators.required, Validators.email, Validators.maxLength(160)]],
    paymentScenario: ['APPROVE' as PaymentScenario],
    items: this.fb.array([this.newItem()]),
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  protected readonly preview = computed(() => {
    const byId = new Map((this.products() ?? []).map((p) => [p.id, p]));
    return (this.value().items ?? []).reduce((sum, i) => {
      const p = i.productId ? byId.get(i.productId) : undefined;
      return sum + (p ? p.price * Math.max(0, Number(i.quantity) || 0) : 0);
    }, 0);
  });

  get items(): FormArray {
    return this.form.controls.items;
  }

  ngOnInit(): void {
    this.api.products({ status: 'ACTIVE', size: 100 }).subscribe({
      next: (page) => this.products.set(page.content),
      error: (err) => {
        this.products.set([]);
        this.error.set(errorMessage(err));
      },
    });
  }

  private newItem() {
    return this.fb.group({
      productId: this.fb.control<number | null>(null, Validators.required),
      quantity: [1, [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)]],
    });
  }

  protected add(): void {
    this.items.push(this.newItem());
  }

  protected remove(i: number): void {
    this.items.removeAt(i);
  }

  protected bad(name: 'customerName' | 'customerEmail'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || c.dirty);
  }

  protected submit(): void {
    const raw = this.form.getRawValue();
    const ids = raw.items.map((i) => i.productId);
    const duplicated = new Set(ids).size !== ids.length;
    this.itemsInvalid.set(this.items.invalid || duplicated);
    if (this.form.invalid || duplicated) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.api
      .createOrder(
        {
          customerName: raw.customerName,
          customerEmail: raw.customerEmail,
          paymentScenario: raw.paymentScenario,
          items: raw.items.map((i) => ({ productId: i.productId as number, quantity: Number(i.quantity) })),
        },
        this.idempotencyKey,
      )
      .subscribe({
        next: (order) => this.router.navigate(['/orders', order.id]),
        error: (err) => {
          this.saving.set(false);
          // Falha de validação ou de estoque: o próximo envio é uma nova tentativa.
          this.idempotencyKey = crypto.randomUUID();
          this.error.set(errorMessage(err));
        },
      });
  }
}
