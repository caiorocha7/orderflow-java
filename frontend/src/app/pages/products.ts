import { CurrencyPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { Auth } from '../core/auth';
import { errorMessage } from '../core/errors';
import { Page, Product, ProductStatus } from '../core/models';
import { Empty, ErrorAlert, Loading } from '../shared/state';

@Component({
  selector: 'of-products',
  imports: [RouterLink, FormsModule, CurrencyPipe, Loading, ErrorAlert, Empty],
  template: `
    <div class="page-header">
      <div>
        <h1>Produtos</h1>
        <p class="muted">Catálogo com preço, status e saldo de estoque.</p>
      </div>
      @if (auth.isAdmin()) {
        <a routerLink="/products/new" class="btn btn--primary">Novo produto</a>
      }
    </div>

    @if (flash) { <div class="alert alert--success" role="status">{{ flash }}</div> }

    <form class="filters" (ngSubmit)="load(0)" role="search">
      <label class="field field--inline">
        <span>Buscar</span>
        <input type="search" name="search" [(ngModel)]="search" placeholder="Nome ou SKU" />
      </label>
      <label class="field field--inline">
        <span>Status</span>
        <select name="status" [(ngModel)]="status" (change)="load(0)">
          <option value="">Todos</option>
          <option value="ACTIVE">Ativos</option>
          <option value="INACTIVE">Inativos</option>
        </select>
      </label>
      <button class="btn" type="submit">Filtrar</button>
    </form>

    @if (error()) {
      <of-error [message]="error()" />
    } @else if (!page()) {
      <of-loading />
    } @else if (page()!.content.length === 0) {
      <of-empty message="Nenhum produto encontrado." />
    } @else {
      <div class="card table-wrap">
        <table class="table">
          <thead>
            <tr><th>SKU</th><th>Nome</th><th class="num">Preço</th><th class="num">Disponível</th><th class="num">Reservado</th><th>Status</th>@if (auth.isAdmin()) {<th><span class="sr-only">Ações</span></th>}</tr>
          </thead>
          <tbody>
            @for (p of page()!.content; track p.id) {
              <tr>
                <td class="mono">{{ p.sku }}</td>
                <td>{{ p.name }}</td>
                <td class="num">{{ p.price | currency }}</td>
                <td class="num">
                  {{ p.availableQuantity }}
                  @if (p.lowStock && p.status === 'ACTIVE') { <span class="badge badge--warning">baixo</span> }
                </td>
                <td class="num">{{ p.reservedQuantity }}</td>
                <td><span class="badge" [class.badge--success]="p.status === 'ACTIVE'" [class.badge--neutral]="p.status === 'INACTIVE'">{{ p.status === 'ACTIVE' ? 'Ativo' : 'Inativo' }}</span></td>
                @if (auth.isAdmin()) {
                  <td class="actions"><a class="link" [routerLink]="['/products', p.id, 'edit']">Editar</a></td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
      <nav class="pager" aria-label="Paginação">
        <button class="btn" type="button" [disabled]="page()!.page === 0" (click)="load(page()!.page - 1)">Anterior</button>
        <span>Página {{ page()!.page + 1 }} de {{ page()!.totalPages || 1 }} · {{ page()!.totalElements }} produtos</span>
        <button class="btn" type="button" [disabled]="page()!.page + 1 >= page()!.totalPages" (click)="load(page()!.page + 1)">Próxima</button>
      </nav>
    }
  `,
})
export class ProductsPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly page = signal<Page<Product> | null>(null);
  protected readonly error = signal('');
  protected search = '';
  protected status: ProductStatus | '' = '';
  /** Mensagem de sucesso enviada pela tela de cadastro via estado da navegação. */
  protected readonly flash = (history.state as { flash?: string } | null)?.flash ?? '';

  ngOnInit(): void {
    this.load(0);
  }

  protected load(pageNumber: number): void {
    this.api.products({ search: this.search, status: this.status, page: pageNumber }).subscribe({
      next: (p) => {
        this.page.set(p);
        this.error.set('');
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
