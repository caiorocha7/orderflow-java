import { Component, computed, input } from '@angular/core';
import { ORDER_STATUS_LABEL, OrderStatus } from '../core/models';

@Component({
  selector: 'of-status',
  template: `<span class="badge" [class]="'badge badge--' + tone()">{{ label() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<OrderStatus>();
  protected readonly label = computed(() => ORDER_STATUS_LABEL[this.status()]);
  protected readonly tone = computed(() => {
    switch (this.status()) {
      case 'PAID':
        return 'success';
      case 'PENDING_PAYMENT':
        return 'info';
      case 'PAYMENT_DECLINED':
      case 'PAYMENT_FAILED':
        return 'danger';
      default:
        return 'neutral';
    }
  });
}
