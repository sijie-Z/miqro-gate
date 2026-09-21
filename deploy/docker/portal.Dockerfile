# MiQroKey Portal image (#479): builds the Vue SPA and serves it through the
# TLS-terminating reverse proxy. Build context is the repository root:
#   docker build -f deploy/docker/portal.Dockerfile .
#
# 不声明 `# syntax=`：受限网络拉不到 docker/dockerfile 前端镜像（实测）；npm cache mount 用内置前端即可。
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS webbuild
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
# npm run build 走 run-p 并行 vue-tsc+vite——实测峰值 ~1.3G，2G 演示机整机被打进
# swap 导致部署期全站超时（#667）；改为串行，峰值 ≈ max(两者) ≈ 740MB。
RUN npm run build-only && npm run typecheck

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
COPY deploy/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=webbuild /web/dist /usr/share/nginx/html
EXPOSE 80 443
