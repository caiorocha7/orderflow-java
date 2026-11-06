import { APIRequestContext, Page, expect, request } from '@playwright/test';

export const ADMIN = { email: 'admin@orderflow.local', password: process.env.DEMO_ADMIN_PASSWORD ?? 'Admin@Demo2026' };
export const OPERATOR = { email: 'operator@orderflow.local', password: process.env.DEMO_OPERATOR_PASSWORD ?? 'Operador@Demo2026' };

export async function login(page: Page, user = ADMIN): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(user.email);
  await page.getByLabel('Senha').fill(user.password);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
}

/** Contexto de API autenticado como admin (mesmo fluxo do navegador: cookie CSRF + sessão). */
export async function adminApi(baseURL: string): Promise<APIRequestContext> {
  const api = await request.newContext({ baseURL });
  await api.get('/api/auth/csrf');
  const xsrf = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
  const res = await api.post('/api/auth/login', { data: ADMIN, headers: { 'X-XSRF-TOKEN': xsrf } });
  expect(res.ok()).toBeTruthy();
  return api;
}

export async function xsrf(api: APIRequestContext): Promise<string> {
  return (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
}

/** Produto exclusivo do teste, para que as contas de estoque não dependam de outros testes. */
export async function createProduct(api: APIRequestContext, name: string, price: number, qty: number) {
  const sku = `E2E-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
  const res = await api.post('/api/products', {
    data: { sku, name, description: 'Criado pelo teste E2E', price, initialQuantity: qty, lowStockThreshold: 1 },
    headers: { 'X-XSRF-TOKEN': await xsrf(api) },
  });
  expect(res.status()).toBe(201);
  return (await res.json()) as { id: number; sku: string; name: string };
}

export async function stockOf(api: APIRequestContext, productId: number) {
  const res = await api.get(`/api/products/${productId}`);
  return (await res.json()) as { availableQuantity: number; reservedQuantity: number };
}
