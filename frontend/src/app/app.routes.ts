import { Routes } from '@angular/router';
import { adminGuard, authGuard } from './core/auth';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login').then((m) => m.LoginPage), title: 'Entrar · OrderFlow' },
  {
    path: '',
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', loadComponent: () => import('./pages/dashboard').then((m) => m.DashboardPage), title: 'Dashboard · OrderFlow' },
      { path: 'products', loadComponent: () => import('./pages/products').then((m) => m.ProductsPage), title: 'Produtos · OrderFlow' },
      { path: 'products/new', canActivate: [adminGuard], loadComponent: () => import('./pages/product-form').then((m) => m.ProductFormPage), title: 'Novo produto · OrderFlow' },
      { path: 'products/:id/edit', canActivate: [adminGuard], loadComponent: () => import('./pages/product-form').then((m) => m.ProductFormPage), title: 'Editar produto · OrderFlow' },
      { path: 'inventory', loadComponent: () => import('./pages/inventory').then((m) => m.InventoryPage), title: 'Estoque · OrderFlow' },
      { path: 'orders', loadComponent: () => import('./pages/orders').then((m) => m.OrdersPage), title: 'Pedidos · OrderFlow' },
      { path: 'orders/new', loadComponent: () => import('./pages/order-new').then((m) => m.OrderNewPage), title: 'Novo pedido · OrderFlow' },
      { path: 'orders/:id', loadComponent: () => import('./pages/order-detail').then((m) => m.OrderDetailPage), title: 'Pedido · OrderFlow' },
      { path: 'processing', canActivate: [adminGuard], loadComponent: () => import('./pages/processing').then((m) => m.ProcessingPage), title: 'Processamento · OrderFlow' },
    ],
  },
  { path: '**', redirectTo: '' },
];
