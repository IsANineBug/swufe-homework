# 构建：在仓库根目录或 /server 目录执行 docker build -t swufe-homework-server server/
# 国内拉取慢时可加 --build-arg GOPROXY=https://goproxy.cn,direct，基础镜像可换成国内镜像源。
FROM golang:1.23-alpine AS build
ARG GOPROXY=https://proxy.golang.org,direct
ENV GOPROXY=${GOPROXY} CGO_ENABLED=0
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 app
COPY --from=build /out/server /usr/local/bin/server
USER app
ENV HTTP_ADDR=:8080 TZ=Asia/Shanghai
EXPOSE 8080
# 端口取自 HTTP_ADDR（形如 :8080 或 0.0.0.0:8080）
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- "http://127.0.0.1:${HTTP_ADDR##*:}/healthz" || exit 1
ENTRYPOINT ["/usr/local/bin/server"]
