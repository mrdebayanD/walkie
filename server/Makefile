# Build the server for the machine it will run on.
#   make            -> ./walkie for this computer
#   make linux-arm64  (Oracle Ampere "A1" free VM)
#   make linux-amd64  (Oracle "E2.1.Micro" free VM, most other servers)
.PHONY: all test linux-arm64 linux-amd64
all:
	go build -trimpath -ldflags="-s -w" -o walkie ./cmd/walkie
test:
	go test -race ./...
linux-arm64:
	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o walkie-linux-arm64 ./cmd/walkie
linux-amd64:
	GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o walkie-linux-amd64 ./cmd/walkie
