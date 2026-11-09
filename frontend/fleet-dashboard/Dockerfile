# syntax=docker/dockerfile:1.7
ARG FRONTEND_NODE_IMAGE=node:22.23.4-alpine3.24@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202
ARG FRONTEND_NGINX_IMAGE=nginx/nginx-unprivileged:1.30.6-alpine3.24@sha256:c3fc8fa482be50ef709c6438eb1bc98d50dbfe5a9b48be90711b8814f4dfd701

FROM ${FRONTEND_NODE_IMAGE} AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY angular.json tsconfig*.json ./
COPY src ./src
COPY public ./public
COPY scripts/check-production.mjs ./scripts/check-production.mjs
RUN npm run build && npm run check:production

FROM ${FRONTEND_NGINX_IMAGE} AS runtime
USER root
RUN rm -rf /usr/share/nginx/html/* /etc/nginx/conf.d/*
COPY --from=build /workspace/dist/fleet-dashboard/browser/ /usr/share/nginx/html/
COPY container/proxy_params /etc/nginx/proxy_params
COPY container/nginx.conf.template /etc/fleetpulse/nginx.conf.template
COPY --chmod=755 container/start.sh /usr/local/bin/start-frontend
COPY --chmod=755 container/healthcheck.sh /usr/local/bin/check-frontend
RUN command -v envsubst && command -v wget \
    && chown -R 0:0 /usr/share/nginx/html /etc/fleetpulse \
    && chmod -R a=rX /usr/share/nginx/html /etc/fleetpulse
USER 101:101
ENV FLEET_API_UPSTREAM=http://fleet-api:8080
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=3 CMD ["/usr/local/bin/check-frontend"]
STOPSIGNAL SIGQUIT
ENTRYPOINT ["/usr/local/bin/start-frontend"]
