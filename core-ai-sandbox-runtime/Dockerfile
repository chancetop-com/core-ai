FROM golang:1.22-alpine AS builder

WORKDIR /app
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o /core-ai-sandbox-runtime .

FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils findutils grep git curl jq \
    libcairo2 libpango-1.0-0 libpangocairo-1.0-0 \
    libgobject-2.0-0 libgdk-pixbuf-2.0-0 libffi-dev \
    shared-mime-info fonts-noto-cjk fontconfig unzip wget && \
    rm -rf /var/lib/apt/lists/*

# Install AlibabaPuHuiTi font (primary Chinese font, fallback URL if CDN unavailable)
RUN mkdir -p /usr/share/fonts/opentype/alibaba && \
    for weight in 55 65 75 85; do \
      for url in \
        "https://cdn.jsdelivr.net/npm/alibaba-puhuiti@3.0/fonts/AlibabaPuHuiTi-3-${weight}-Bold.otf" \
        "https://raw.githubusercontent.com/alibabafonts/alibaba-puhuiti-3/main/source/AlibabaPuHuiTi-3-${weight}-Bold.otf"; do \
        wget -q --timeout=10 "$url" -O "/usr/share/fonts/opentype/alibaba/puhuiti-${weight}-bold.otf" 2>/dev/null && break; \
      done; \
      for url in \
        "https://cdn.jsdelivr.net/npm/alibaba-puhuiti@3.0/fonts/AlibabaPuHuiTi-3-${weight}-Regular.otf" \
        "https://raw.githubusercontent.com/alibabafonts/alibaba-puhuiti-3/main/source/AlibabaPuHuiTi-3-${weight}-Regular.otf"; do \
        wget -q --timeout=10 "$url" -O "/usr/share/fonts/opentype/alibaba/puhuiti-${weight}-regular.otf" 2>/dev/null && break; \
      done; \
    done; \
    fc-cache -fv

RUN pip install --no-cache-dir weasyprint
RUN groupadd -g 1001 sandbox && useradd -u 1001 -g sandbox -m sandbox
COPY --from=builder /core-ai-sandbox-runtime /usr/local/bin/core-ai-sandbox-runtime
USER 1001
WORKDIR /workspace
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/core-ai-sandbox-runtime"]
