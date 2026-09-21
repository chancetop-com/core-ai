set -e
cd "$(dirname "$0")"
mkdir -p /tmp/bins /tmp/chk
for f in cli.go cli_render.go cli_dataset.go cli_dataset_test.go bind.go hubproxy.go hub_test.go main.go; do
  tr -d '\r' < "$f" > "/tmp/chk/$f"
done
echo '--- gofmt (new/edited files, LF-normalized) ---'
gofmt -l /tmp/chk/*.go
echo '--- vet ---'
go vet ./...
echo VET_OK
echo '--- test (full suite) ---'
go test ./... 2>&1 | tail -30
echo '--- build ---'
go build -o /tmp/bins/ ./...
ls -l /tmp/bins
echo BUILD_OK
