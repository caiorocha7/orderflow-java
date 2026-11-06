# OrderFlow · frontend

Angular 22 (componentes standalone, signals, sem zone.js). Em produção é servido pelo Nginx, que também encaminha
`/api` para o backend (mesma origem). Veja o [README principal](../README.md).

```bash
npm ci
npx ng serve --proxy-config proxy.conf.json   # http://localhost:4200, API em localhost:8080
npx ng test --watch=false                     # Vitest
npx ng build                                  # build de produção em dist/frontend/browser
```

Para o `ng serve`, rode o backend localmente na porta 8080 (por exemplo, `TestOrderflowApplication`, que sobe
PostgreSQL e Kafka via Testcontainers).
