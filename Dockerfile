# Use official Go image as builder
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -ldflags="-s -w" -o meli-server ./cmd/server
RUN go build -ldflags="-s -w" -o meli-client ./cmd/client 2>/dev/null || true

# Final stage: small runtime image
FROM alpine:3.20
RUN apk add --no-cache ca-certificates
WORKDIR /root/
COPY --from=builder /app/meli-server .
COPY --from=builder /app/meli-client .
EXPOSE 5053
# Default command runs server
ENTRYPOINT ["/root/meli-server"]
