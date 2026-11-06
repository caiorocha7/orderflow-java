import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { ADMIN, OPERATOR, adminApi, createProduct, login, stockOf } from './support';

let api: APIRequestContext;

test.beforeAll(async ({ baseURL }) => {
  api = await adminApi(baseURL!);
});

test.afterAll(async () => {
  await api.dispose();
});

async function placeOrder(page: Page, productLabel: string, qty: number, scenario: string): Promise<void> {
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  await page.getByLabel('Cliente', { exact: true }).fill('Cliente E2E');
  await page.getByLabel('E-mail do cliente').fill('cliente.e2e@example.com');
  await page.getByTestId('scenario').selectOption(scenario);
  await page.getByTestId('item-product-0').selectOption({ label: productLabel });
  await page.getByTestId('item-qty-0').fill(String(qty));
  await page.getByTestId('submit-order').click();
  await expect(page).toHaveURL(/\/orders\/\d+$/);
}

async function optionLabel(page: Page, productName: string): Promise<string> {
  const option = page.getByTestId('item-product-0').locator('option', { hasText: productName });
  return (await option.textContent())!.trim();
}

test('login inválido mostra erro genérico e login válido abre o dashboard com dados do banco', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(ADMIN.email);
  await page.getByLabel('Senha').fill('senha-errada');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('alert')).toHaveText('E-mail ou senha inválidos');

  await login(page);
  await expect(page.getByTestId('kpi-products')).toHaveText(/^\d+$/);
  // Nenhum token é guardado no navegador: a sessão é só o cookie HttpOnly.
  expect(await page.evaluate(() => Object.keys(localStorage).length)).toBe(0);
  const cookies = await page.context().cookies();
  expect(cookies.find((c) => c.name === 'JSESSIONID')?.httpOnly).toBe(true);
});

test('admin cadastra produto e ele aparece no catálogo', async ({ page }) => {
  const sku = `UI-${Date.now()}`;
  await login(page);
  await page.getByRole('link', { name: 'Produtos' }).click();
  await page.getByRole('link', { name: 'Novo produto' }).click();
  await page.getByLabel('SKU').fill(sku);
  await page.getByLabel('Nome').fill('Hub USB E2E');
  await page.getByLabel('Preço (R$)').fill('199.90');
  await page.getByLabel('Estoque inicial').fill('12');
  await page.getByRole('button', { name: 'Salvar' }).click();

  await expect(page.getByText('Produto salvo com sucesso.')).toBeVisible();
  await page.getByPlaceholder('Nome ou SKU').fill(sku);
  await page.getByRole('button', { name: 'Filtrar' }).click();
  await expect(page.getByRole('cell', { name: sku.toUpperCase() })).toBeVisible();
});

test('pedido aprovado via Kafka consome a reserva de estoque', async ({ page }) => {
  const product = await createProduct(api, `Teclado E2E ${Date.now()}`, 250, 10);
  await login(page, OPERATOR);
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  const label = await optionLabel(page, product.name);
  await placeOrder(page, label, 3, 'APPROVE');

  await expect(page.getByTestId('order-total')).toHaveText(/750,00/);
  await expect(page.getByTestId('order-status')).toHaveText('Pago');
  await expect(page.getByTestId('consumption')).toContainText('Processado');
  const stock = await stockOf(api, product.id);
  expect(stock).toEqual(expect.objectContaining({ availableQuantity: 7, reservedQuantity: 0 }));
});

test('pagamento recusado libera a reserva', async ({ page }) => {
  const product = await createProduct(api, `Mouse E2E ${Date.now()}`, 80, 5);
  await login(page, OPERATOR);
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  await placeOrder(page, await optionLabel(page, product.name), 5, 'DECLINE');

  await expect(page.getByTestId('order-status')).toHaveText('Pagamento recusado');
  await expect(page.getByTestId('history')).toContainText('Pagamento recusado');
  expect(await stockOf(api, product.id)).toEqual(expect.objectContaining({ availableQuantity: 5, reservedQuantity: 0 }));
});

test('falha temporária é retentada e o pedido termina pago', async ({ page }) => {
  const product = await createProduct(api, `Monitor E2E ${Date.now()}`, 1200, 2);
  await login(page, OPERATOR);
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  await placeOrder(page, await optionLabel(page, product.name), 1, 'TRANSIENT_FAILURE');

  await expect(page.getByTestId('order-status')).toHaveText('Pago', { timeout: 30_000 });
  await expect(page.getByTestId('consumption').getByText('Falha temporária · nova tentativa')).toHaveCount(2);
});

test('pedido pendente pode ser cancelado e o estoque volta', async ({ page }) => {
  const product = await createProduct(api, `Dock E2E ${Date.now()}`, 400, 4);
  await login(page, OPERATOR);
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  // Gateway indisponível mantém o pedido pendente por alguns segundos (retentativas).
  await placeOrder(page, await optionLabel(page, product.name), 2, 'GATEWAY_DOWN');
  await page.getByTestId('cancel-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('Cancelado');
  expect(await stockOf(api, product.id)).toEqual(expect.objectContaining({ availableQuantity: 4, reservedQuantity: 0 }));
});

test('falta de estoque é recusada com mensagem clara e nada é reservado', async ({ page }) => {
  const product = await createProduct(api, `Escasso E2E ${Date.now()}`, 10, 1);
  await login(page, OPERATOR);
  await page.getByRole('link', { name: 'Novo pedido' }).click();
  await page.getByLabel('Cliente', { exact: true }).fill('Cliente E2E');
  await page.getByLabel('E-mail do cliente').fill('cliente.e2e@example.com');
  await page.getByTestId('item-product-0').selectOption({ label: await optionLabel(page, product.name) });
  await page.getByTestId('item-qty-0').fill('3');
  await page.getByTestId('submit-order').click();

  await expect(page.getByTestId('order-error')).toContainText('Estoque insuficiente');
  expect(await stockOf(api, product.id)).toEqual(expect.objectContaining({ availableQuantity: 1, reservedQuantity: 0 }));
});

test('operador não vê nem acessa o histórico global de processamento; admin acessa', async ({ page, browser }) => {
  await login(page, OPERATOR);
  await expect(page.getByRole('link', { name: 'Processamento' })).toHaveCount(0);
  await page.goto('/processing');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('link', { name: 'Novo produto' })).toHaveCount(0);

  const adminPage = await browser.newPage();
  await login(adminPage, ADMIN);
  await adminPage.getByRole('link', { name: 'Processamento' }).click();
  await expect(adminPage.getByRole('heading', { name: 'Histórico de processamento' })).toBeVisible();
  await expect(adminPage.getByText('Enviados à DLT')).toBeVisible();
  await adminPage.close();
});

test('layout responsivo no celular: menu recolhível e sem rolagem horizontal', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  const noOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  expect(await noOverflow()).toBe(true); // dashboard com tabela de pedidos recentes
  const menu = page.getByRole('button', { name: 'Abrir menu' });
  await expect(menu).toBeVisible();
  await menu.click();
  await page.getByRole('link', { name: 'Pedidos' }).click();
  await expect(page.getByRole('heading', { name: 'Pedidos' })).toBeVisible();
  expect(await noOverflow()).toBe(true);
});

test('menu recolhível não aparece no desktop', async ({ page }) => {
  await login(page);
  await expect(page.getByRole('button', { name: 'Abrir menu' })).toBeHidden();
});
