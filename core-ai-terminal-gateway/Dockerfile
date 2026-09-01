FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
COPY VERSION ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-X main.gatewayVersion=$(cat VERSION)" -o /core-ai-terminal-gateway .

FROM gcr.io/distroless/static
COPY --from=builder /core-ai-terminal-gateway /core-ai-terminal-gateway
EXPOSE 8080
ENTRYPOINT ["/core-ai-terminal-gateway"]
