import { Component, input } from '@angular/core';

/** Estados de carregamento, vazio e erro reutilizados nas telas. */
@Component({
  selector: 'of-loading',
  template: `<div class="state" role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span>{{ label() }}</div>`,
})
export class Loading {
  readonly label = input('Carregando...');
}

@Component({
  selector: 'of-error',
  template: `<div class="alert alert--danger" role="alert">{{ message() }}</div>`,
})
export class ErrorAlert {
  readonly message = input.required<string>();
}

@Component({
  selector: 'of-empty',
  template: `<div class="state state--empty">{{ message() }}</div>`,
})
export class Empty {
  readonly message = input.required<string>();
}
