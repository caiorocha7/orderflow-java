import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateOrderRequest,
  CreateProductRequest,
  Dashboard,
  OrderDetail,
  OrderProcessing,
  OrderStatus,
  OrderSummary,
  Page,
  ProcessingOverview,
  Product,
  ProductStatus,
  StockMovement,
  UpdateProductRequest,
} from './models';

/** Cliente da API REST. Todas as URLs são relativas: em produção o Nginx serve frontend e API na mesma origem. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  dashboard(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/dashboard');
  }

  products(filter: { search?: string; status?: ProductStatus | ''; page?: number; size?: number } = {}) {
    let params = new HttpParams().set('page', filter.page ?? 0).set('size', filter.size ?? 20);
    if (filter.search) params = params.set('search', filter.search);
    if (filter.status) params = params.set('status', filter.status);
    return this.http.get<Page<Product>>('/api/products', { params });
  }

  product(id: number) {
    return this.http.get<Product>(`/api/products/${id}`);
  }

  createProduct(body: CreateProductRequest) {
    return this.http.post<Product>('/api/products', body);
  }

  updateProduct(id: number, body: UpdateProductRequest) {
    return this.http.put<Product>(`/api/products/${id}`, body);
  }

  adjustStock(id: number, availableQuantity: number, reason: string) {
    return this.http.post<Product>(`/api/products/${id}/stock-adjustments`, { availableQuantity, reason });
  }

  stockMovements(id: number) {
    return this.http.get<StockMovement[]>(`/api/products/${id}/stock-movements`);
  }

  orders(filter: { status?: OrderStatus | ''; page?: number; size?: number } = {}) {
    let params = new HttpParams().set('page', filter.page ?? 0).set('size', filter.size ?? 20);
    if (filter.status) params = params.set('status', filter.status);
    return this.http.get<Page<OrderSummary>>('/api/orders', { params });
  }

  order(id: number) {
    return this.http.get<OrderDetail>(`/api/orders/${id}`);
  }

  orderProcessing(id: number) {
    return this.http.get<OrderProcessing>(`/api/orders/${id}/processing`);
  }

  /** A chave de idempotência evita pedidos duplicados em duplo clique ou reenvio. */
  createOrder(body: CreateOrderRequest, idempotencyKey: string) {
    return this.http.post<OrderDetail>('/api/orders', body, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  cancelOrder(id: number, reason: string) {
    return this.http.post<OrderDetail>(`/api/orders/${id}/cancel`, { reason });
  }

  processing(limit = 30) {
    return this.http.get<ProcessingOverview>('/api/processing', { params: { limit } });
  }
}
