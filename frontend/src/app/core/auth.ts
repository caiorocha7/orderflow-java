import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, switchMap, tap, throwError } from 'rxjs';
import { Me } from './models';

/**
 * Sessão baseada em cookie HttpOnly emitido pelo backend: o JavaScript nunca vê nem guarda tokens
 * (nada em localStorage). O token CSRF fica no cookie XSRF-TOKEN e o HttpClient o envia no header
 * X-XSRF-TOKEN automaticamente em requisições de escrita.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly state = signal<Me | null | undefined>(undefined);

  readonly user = this.state.asReadonly();
  readonly isAdmin = computed(() => this.state()?.role === 'ADMIN');

  /** Restaura a sessão existente (se houver) ao abrir a aplicação. */
  load(): Observable<Me | null> {
    if (this.state() !== undefined) return of(this.state() ?? null);
    return this.http.get<Me>('/api/auth/me').pipe(
      catchError(() => of(null)),
      tap((me) => this.state.set(me)),
    );
  }

  login(email: string, password: string): Observable<Me> {
    return this.http.get<void>('/api/auth/csrf').pipe(
      switchMap(() => this.http.post<Me>('/api/auth/login', { email, password })),
      tap((me) => this.state.set(me)),
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}).pipe(
      catchError(() => of(undefined)),
      tap(() => this.state.set(null)),
      map(() => undefined),
    );
  }

  clear(): void {
    this.state.set(null);
  }
}

export const authGuard: CanActivateFn = (_route, state) => {
  const router = inject(Router);
  return inject(Auth)
    .load()
    .pipe(map((me) => (me ? true : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } }))));
};

export const adminGuard: CanActivateFn = () => {
  const router = inject(Router);
  return inject(Auth)
    .load()
    .pipe(map((me) => (me?.role === 'ADMIN' ? true : router.createUrlTree(['/dashboard']))));
};

/** Sessão expirada em qualquer chamada: limpa o estado e volta ao login. */
export const sessionExpiredInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.startsWith('/api/auth/')) {
        auth.clear();
        router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return throwError(() => err);
    }),
  );
};
