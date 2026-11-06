import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Product } from '../core/models';
import { OrderNewPage } from './order-new';

registerLocaleData(localePt);

const product = (id: number, price: number, available = 10): Product => ({
  id, sku: `SKU-${id}`, name: `Produto ${id}`, description: null, price, status: 'ACTIVE',
  availableQuantity: available, reservedQuantity: 0, lowStockThreshold: 1, lowStock: false, version: 0,
  createdAt: '', updatedAt: '',
});

describe('OrderNewPage', () => {
  let http: HttpTestingController;

  async function setup() {
    TestBed.configureTestingModule({
      imports: [OrderNewPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: LOCALE_ID, useValue: 'pt-BR' }],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(OrderNewPage);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/products').flush({
      content: [product(1, 100), product(2, 25.5)], page: 0, size: 100, totalElements: 2, totalPages: 1,
    });
    await fixture.whenStable();
    // Acesso aos membros protegidos do componente apenas no teste.
    return { fixture, page: fixture.componentInstance as any };
  }

  afterEach(() => http.verify());

  it('calcula apenas uma prévia do total a partir dos preços carregados', async () => {
    const { page } = await setup();
    page.items.at(0).patchValue({ productId: 1, quantity: 2 });
    page.add();
    page.items.at(1).patchValue({ productId: 2, quantity: 3 });
    expect(page.preview()).toBeCloseTo(276.5);
  });

  it('não envia pedido com produto repetido', async () => {
    const { page } = await setup();
    page.form.patchValue({ customerName: 'Ana', customerEmail: 'ana@example.com' });
    page.items.at(0).patchValue({ productId: 1, quantity: 1 });
    page.add();
    page.items.at(1).patchValue({ productId: 1, quantity: 1 });
    page.submit();
    expect(page.itemsInvalid()).toBe(true);
    http.expectNone('/api/orders');
  });

  it('envia só ids e quantidades, com Idempotency-Key, e abre o pedido criado', async () => {
    const { page } = await setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    page.form.patchValue({ customerName: 'Ana', customerEmail: 'ana@example.com', paymentScenario: 'DECLINE' });
    page.items.at(0).patchValue({ productId: 2, quantity: 4 });
    page.submit();

    const req = http.expectOne({ method: 'POST', url: '/api/orders' });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(req.request.body).toEqual({
      customerName: 'Ana', customerEmail: 'ana@example.com', paymentScenario: 'DECLINE',
      items: [{ productId: 2, quantity: 4 }],
    });
    req.flush({ id: 77 });
    expect(navigate).toHaveBeenCalledWith(['/orders', 77]);
  });
});
