export type Role = 'ADMIN' | 'OPERATOR';
export type ProductStatus = 'ACTIVE' | 'INACTIVE';
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'PAYMENT_DECLINED' | 'PAYMENT_FAILED' | 'CANCELLED';
export type PaymentScenario = 'APPROVE' | 'DECLINE' | 'TRANSIENT_FAILURE' | 'GATEWAY_DOWN';

export interface Me {
  email: string;
  fullName: string;
  role: Role;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  price: number;
  status: ProductStatus;
  availableQuantity: number;
  reservedQuantity: number;
  lowStockThreshold: number;
  lowStock: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface StockMovement {
  id: number;
  type: 'INITIAL' | 'ADJUSTMENT' | 'RESERVE' | 'RELEASE' | 'CONSUME' | 'RETURN';
  quantity: number;
  orderId: number | null;
  reason: string | null;
  createdBy: string;
  createdAt: string;
}

export interface OrderSummary {
  id: number;
  code: string;
  customerName: string;
  status: OrderStatus;
  paymentScenario: PaymentScenario;
  totalAmount: number;
  itemCount: number;
  createdBy: string;
  createdAt: string;
}

export interface OrderItem {
  productId: number;
  sku: string;
  productName: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface StatusChange {
  fromStatus: OrderStatus | null;
  toStatus: OrderStatus;
  reason: string | null;
  changedBy: string;
  changedAt: string;
}

export interface OrderDetail {
  id: number;
  code: string;
  customerName: string;
  customerEmail: string;
  status: OrderStatus;
  paymentScenario: PaymentScenario;
  totalAmount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  cancellable: boolean;
  items: OrderItem[];
  history: StatusChange[];
}

export interface OutboxEventView {
  id: string;
  eventType: string;
  aggregateId: string;
  createdAt: string;
  publishedAt: string | null;
  publishAttempts: number;
  lastError: string | null;
}

export type ProcessingOutcome = 'PROCESSED' | 'DUPLICATE_IGNORED' | 'SKIPPED' | 'RETRYING' | 'DEAD_LETTERED';

export interface ProcessingLogView {
  id: number;
  eventId: string;
  eventType: string;
  orderId: number | null;
  consumer: string;
  outcome: ProcessingOutcome;
  attempt: number;
  detail: string | null;
  createdAt: string;
}

export interface PaymentAttemptView {
  attempt: number;
  outcome: 'APPROVED' | 'DECLINED' | 'TRANSIENT_FAILURE';
  simulatedReference: string | null;
  createdAt: string;
}

export interface OrderProcessing {
  events: OutboxEventView[];
  consumption: ProcessingLogView[];
  paymentAttempts: PaymentAttemptView[];
}

export interface ProcessingOverview {
  pendingOutbox: number;
  processed: number;
  duplicatesIgnored: number;
  retries: number;
  deadLettered: number;
  recentEvents: OutboxEventView[];
  recentConsumption: ProcessingLogView[];
}

export interface Dashboard {
  totalOrders: number;
  pendingOrders: number;
  paidOrders: number;
  declinedOrders: number;
  failedOrders: number;
  cancelledOrders: number;
  totalProducts: number;
  activeProducts: number;
  lowStock: Product[];
  recentOrders: OrderSummary[];
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description: string | null;
  price: number;
  initialQuantity: number;
  lowStockThreshold: number;
}

export interface UpdateProductRequest {
  name: string;
  description: string | null;
  price: number;
  lowStockThreshold: number;
  status: ProductStatus;
  version: number;
}

export interface CreateOrderRequest {
  customerName: string;
  customerEmail: string;
  paymentScenario: PaymentScenario;
  items: { productId: number; quantity: number }[];
}

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Aguardando pagamento',
  PAID: 'Pago',
  PAYMENT_DECLINED: 'Pagamento recusado',
  PAYMENT_FAILED: 'Falha no pagamento',
  CANCELLED: 'Cancelado',
};

export const PAYMENT_SCENARIO_LABEL: Record<PaymentScenario, string> = {
  APPROVE: 'Aprovar',
  DECLINE: 'Recusar',
  TRANSIENT_FAILURE: 'Falha temporária (aprova na 3ª tentativa)',
  GATEWAY_DOWN: 'Gateway indisponível (vai para a DLT)',
};
