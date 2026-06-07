.PHONY: run build test vet docker

run:
	go run ./cmd/api

build:
	go build -o bin/api ./cmd/api
	go build -o bin/worker ./cmd/worker

test:
	go test ./...

vet:
	go vet ./...

docker:
	docker build -t ledger-api .

# Run a quick health check against a local instance
health:
	curl -s http://localhost:8080/v1/health | jq .
