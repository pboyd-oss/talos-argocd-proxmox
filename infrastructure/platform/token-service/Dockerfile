FROM harbor.tuxgrid.com/docker.io/golang:1.23-alpine AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /token-service .

FROM harbor.tuxgrid.com/sigstore/cosign:v2.5.2 AS cosign

FROM harbor.tuxgrid.com/docker.io/alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=builder /token-service /token-service
COPY --from=cosign /ko-app/cosign /usr/local/bin/cosign
ENTRYPOINT ["/token-service"]
