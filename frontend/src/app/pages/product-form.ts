import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { errorCode, errorMessage } from '../core/errors';
import { Product, ProductStatus } from '../core/models';
import { Loading } from '../shared/state';

@Component({
  selector: 'of-product-form',
  imports: [ReactiveFormsModule, RouterLink, Loading],
  template: `
    <div class="page-header">
      <div>
        <h1>{{ id() ? 'Editar produto' : 'Novo produto' }}</h1>
        <p class="muted">{{ id() ? 'O estoque é alterado na tela de Estoque, com motivo registrado.' : 'O estoque inicial gera uma movimentação de entrada.' }}</p>
      </div>
      <a routerLink="/products" class="btn btn--ghost">Voltar</a>
    </div>

    @if (id() && !loaded()) {
      <of-loading />
    } @else {
      <form class="card form" [formGroup]="form" (ngSubmit)="save()" novalidate>
        @if (error()) {
          <div class="alert alert--danger" role="alert">{{ error() }}</div>
        }
        <div class="form-grid">
          <label class="field">
            <span>SKU</span>
            <input formControlName="sku" [readonly]="!!id()" placeholder="EX-001" [attr.aria-invalid]="bad('sku')" />
            @if (bad('sku')) { <small class="field__error">Obrigatório: letras, números, ponto, hífen ou sublinhado.</small> }
          </label>
          <label class="field">
            <span>Nome</span>
            <input formControlName="name" [attr.aria-invalid]="bad('name')" />
            @if (bad('name')) { <small class="field__error">Informe o nome (até 120 caracteres).</small> }
          </label>
          <label class="field field--wide">
            <span>Descrição (opcional)</span>
            <textarea formControlName="description" rows="2"></textarea>
          </label>
          <label class="field">
            <span>Preço (R$)</span>
            <input type="number" step="0.01" min="0" formControlName="price" [attr.aria-invalid]="bad('price')" />
            @if (bad('price')) { <small class="field__error">O preço não pode ser negativo.</small> }
          </label>
          @if (!id()) {
            <label class="field">
              <span>Estoque inicial</span>
              <input type="number" min="0" step="1" formControlName="initialQuantity" [attr.aria-invalid]="bad('initialQuantity')" />
              @if (bad('initialQuantity')) { <small class="field__error">Informe um número inteiro maior ou igual a zero.</small> }
            </label>
          } @else {
            <label class="field">
              <span>Status</span>
              <select formControlName="status">
                <option value="ACTIVE">Ativo</option>
                <option value="INACTIVE">Inativo</option>
              </select>
            </label>
          }
          <label class="field">
            <span>Alerta de estoque baixo (≤)</span>
            <input type="number" min="0" step="1" formControlName="lowStockThreshold" [attr.aria-invalid]="bad('lowStockThreshold')" />
            @if (bad('lowStockThreshold')) { <small class="field__error">Informe um número inteiro maior ou igual a zero.</small> }
          </label>
        </div>
        <div class="form-actions">
          <button class="btn btn--primary" type="submit" [disabled]="saving()">{{ saving() ? 'Salvando...' : 'Salvar' }}</button>
        </div>
      </form>
    }
  `,
})
export class ProductFormPage implements OnInit {
  /** Vem da rota (products/:id/edit) via withComponentInputBinding. */
  readonly id = input<string>();
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly loaded = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  private version = 0;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    sku: ['', [Validators.required, Validators.maxLength(40), Validators.pattern(/^[A-Za-z0-9._-]+$/)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: [''],
    price: [0, [Validators.required, Validators.min(0)]],
    initialQuantity: [0, [Validators.required, Validators.min(0), Validators.pattern(/^\d+$/)]],
    lowStockThreshold: [5, [Validators.required, Validators.min(0), Validators.pattern(/^\d+$/)]],
    status: ['ACTIVE' as ProductStatus],
  });

  ngOnInit(): void {
    const id = this.id();
    if (!id) return;
    this.form.controls.initialQuantity.disable();
    this.api.product(Number(id)).subscribe({
      next: (p) => this.fill(p),
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loaded.set(true);
      },
    });
  }

  private fill(p: Product): void {
    this.version = p.version;
    this.form.patchValue({ sku: p.sku, name: p.name, description: p.description ?? '', price: p.price, lowStockThreshold: p.lowStockThreshold, status: p.status });
    this.loaded.set(true);
  }

  protected bad(name: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || c.dirty);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const v = this.form.getRawValue();
    const id = this.id();
    const request = id
      ? this.api.updateProduct(Number(id), { name: v.name, description: v.description || null, price: Number(v.price), lowStockThreshold: Number(v.lowStockThreshold), status: v.status, version: this.version })
      : this.api.createProduct({ sku: v.sku, name: v.name, description: v.description || null, price: Number(v.price), initialQuantity: Number(v.initialQuantity), lowStockThreshold: Number(v.lowStockThreshold) });
    request.subscribe({
      next: () => this.router.navigate(['/products'], { state: { flash: 'Produto salvo com sucesso.' } }),
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorCode(err) === 'STALE_VERSION'
          ? 'Outra pessoa alterou este produto enquanto você editava. Recarregue a página para ver a versão atual.'
          : errorMessage(err));
      },
    });
  }
}
