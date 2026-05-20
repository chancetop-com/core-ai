FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /core-ai-sandbox-runtime .

FROM python:3.12-slim

# Layer 1: system packages (rarely changes, heavy but cacheable)
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils findutils grep git curl jq \
    nodejs npm \
    libcairo2 libpango-1.0-0 libpangocairo-1.0-0 \
    libgobject-2.0-0 libgdk-pixbuf-2.0-0 libffi-dev \
    shared-mime-info fonts-noto-cjk fontconfig \
    chromium xvfb && \
    rm -rf /var/lib/apt/lists/*

# Layer 2: python deps (changes occasionally)
RUN pip install --no-cache-dir weasyprint

# Layer 2b: scientific & plotting stack (preinstalled so agents don't pip install each time)
RUN pip install --no-cache-dir \
    numpy pandas scipy sympy \
    matplotlib seaborn plotly kaleido \
    scikit-learn statsmodels \
    pillow openpyxl

# Layer 2c: browser automation (nodriver for non-headless chromium with xvfb)
RUN pip install --no-cache-dir nodriver

# Layer 3: gh CLI (version bumps only)
RUN curl -fsSL https://github.com/cli/cli/releases/download/v2.92.0/gh_2.92.0_linux_amd64.tar.gz -o /tmp/gh.tar.gz && \
    tar xzf /tmp/gh.tar.gz -C /tmp && \
    mv /tmp/gh_2.92.0_linux_amd64/bin/gh /usr/local/bin/gh && \
    chmod +x /usr/local/bin/gh && \
    rm -rf /tmp/gh*

RUN groupadd -g 1001 sandbox && useradd -u 1001 -g sandbox -m sandbox
COPY --from=builder /core-ai-sandbox-runtime /usr/local/bin/core-ai-sandbox-runtime

# xvfb display for non-headless browser automation
ENV DISPLAY=:99
# chromium needs writable user-data-dir; rootfs is read-only, point HOME to writable /tmp
ENV HOME=/tmp

USER 1001
WORKDIR /workspace
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/core-ai-sandbox-runtime"]
