// Gera as capturas de tela do README a partir da aplicação real (docker compose).
// Uso: com a stack recém-criada (docker compose down -v && docker compose up -d --build):
//   node capture-screenshots.mjs
// Cria alguns pedidos de demonstração pela própria API (um por cenário de pagamento simulado).
import { chromium, request } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:8081';
const OUT = resolve(import.meta.dirname, '../docs/screenshots');
const ADMIN = { email: 'admin@orderflow.local', password: process.env.DEMO_ADMIN_PASSWORD ?? 'Admin@Demo2026' };
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api() {
  const ctx = await request.newContext({ baseURL: BASE });
  await ctx.get('/api/auth/csrf');
  const token = async () => (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN').value;
  const res = await ctx.post('/api/auth/login', { data: ADMIN, headers: { 'X-XSRF-TOKEN': await token() } });
  if (!res.ok()) throw new Error('login falhou: ' + res.status());
  return { ctx, token };
}

async function seedOrders({ ctx, token }) {
  const products = (await (await ctx.get('/api/products?size=50')).json()).content;
  const bySku = Object.fromEntries(products.map((p) => [p.sku, p.id]));
  const orders = [
    { customerName: 'Mariana Alves', paymentScenario: 'APPROVE', items: [['DEMO-KB-001', 2], ['DEMO-MS-002', 2]] },
    { customerName: 'Rafael Costa', paymentScenario: 'TRANSIENT_FAILURE', items: [['DEMO-MN-003', 1], ['DEMO-CB-006', 2]] },
    { customerName: 'Juliana Prado', paymentScenario: 'DECLINE', items: [['DEMO-SSD-008', 3]] },
    { customerName: 'Bruno Lima', paymentScenario: 'APPROVE', items: [['DEMO-WC-007', 1], ['DEMO-HS-004', 1]] },
    { customerName: 'Carla Menezes', paymentScenario: 'GATEWAY_DOWN', items: [['DEMO-DK-005', 1]] },
    { customerName: 'Diego Souza', paymentScenario: 'APPROVE', items: [['DEMO-CB-006', 5]] },
  ];
  const ids = [];
  for (const o of orders) {
    const res = await ctx.post('/api/orders', {
      data: {
        customerName: o.customerName,
        customerEmail: o.customerName.toLowerCase().replace(' ', '.') + '@example.com',
        paymentScenario: o.paymentScenario,
        items: o.items.map(([sku, quantity]) => ({ productId: bySku[sku], quantity })),
      },
      headers: { 'X-XSRF-TOKEN': await token(), 'Idempotency-Key': crypto.randomUUID() },
    });
    if (res.status() !== 201) throw new Error('pedido falhou: ' + (await res.text()));
    ids.push((await res.json()).id);
  }
  return ids;
}

const ctx = await api();
const ids = await seedOrders(ctx);
console.log('Aguardando o processamento assíncrono (retentativas e DLT)...');
await sleep(12_000);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const shot = async (name, path, opts = {}) => {
  await page.goto(BASE + path);
  await page.waitForLoadState('networkidle');
  if (opts.click) await page.getByRole('button', { name: opts.click }).first().click();
  if (opts.wait) await page.waitForTimeout(opts.wait);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: opts.full ?? false });
  console.log('ok', name);
};

await shot('01-login', '/login');
await page.getByLabel('E-mail').fill(ADMIN.email);
await page.getByLabel('Senha').fill(ADMIN.password);
await page.getByRole('button', { name: 'Entrar' }).click();
await page.waitForURL('**/dashboard');

await shot('02-dashboard', '/dashboard', { full: true });
await shot('03-orders', '/orders');
await shot('04-order-paid', `/orders/${ids[0]}`, { full: true });
await shot('05-order-retry', `/orders/${ids[1]}`, { full: true });
await shot('06-order-dlt', `/orders/${ids[4]}`, { full: true });
await shot('07-new-order', '/orders/new');
await shot('08-products', '/products');
await page.goto(BASE + '/inventory');
await page.getByRole('button', { name: 'Movimentações' }).first().click();
await page.waitForTimeout(800);
await page.screenshot({ path: `${OUT}/09-inventory.png` });
console.log('ok', '09-inventory');
await shot('10-processing', '/processing', { full: true });

const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
await mobile.goto(BASE + '/login');
await mobile.getByLabel('E-mail').fill(ADMIN.email);
await mobile.getByLabel('Senha').fill(ADMIN.password);
await mobile.getByRole('button', { name: 'Entrar' }).click();
await mobile.waitForURL('**/dashboard');
await mobile.waitForLoadState('networkidle');
await mobile.screenshot({ path: `${OUT}/11-mobile-dashboard.png` });
console.log('ok', '11-mobile-dashboard');

await browser.close();
await ctx.ctx.dispose();
