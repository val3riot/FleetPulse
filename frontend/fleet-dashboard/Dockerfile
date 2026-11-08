# syntax=docker/dockerfile:1.7
ARG FRONTEND_NODE_IMAGE=node:22.23.2-alpine3.23@sha256:72c5815a06aed9a2273aea5628d74d348af57843a7b547af2fe53dd3e4b95261
ARG FRONTEND_NGINX_IMAGE=nginx/nginx-unprivileged:1.30.6-alpine@sha256:44e74ca483234093081138b6d7b6f561d55557756a2deefc3a5056148defeb99

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
