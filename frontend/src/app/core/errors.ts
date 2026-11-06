import { HttpErrorResponse } from '@angular/common/http';

interface Problem {
  detail?: string;
  code?: string;
  errors?: Record<string, string>;
}

/** Converte respostas de erro (RFC 9457) em mensagens amigáveis em português. */
export function errorMessage(err: unknown, fallback = 'Não foi possível concluir a operação.'): string {
  if (!(err instanceof HttpErrorResponse)) return fallback;
  if (err.status === 0) return 'Sem conexão com o servidor. Verifique se a API está no ar.';
  const problem = (err.error ?? {}) as Problem;
  if (problem.errors && Object.keys(problem.errors).length) {
    return Object.entries(problem.errors)
      .map(([field, msg]) => `${field}: ${msg}`)
      .join(' · ');
  }
  if (err.status === 403) return problem.detail ?? 'Você não tem permissão para esta operação.';
  return problem.detail ?? fallback;
}

export function errorCode(err: unknown): string | undefined {
  return err instanceof HttpErrorResponse ? ((err.error ?? {}) as Problem).code : undefined;
}
