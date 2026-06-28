FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /core-ai-sandbox-runtime .

FROM python:3.12-slim

# Layer 1: system packages (rarely changes, heavy but cacheable)
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils findutils grep git curl jq \
    gcc g++ make python3-dev gyp \
    libcairo2 libpango-1.0-0 libpangocairo-1.0-0 \
    libgobject-2.0-0 libgdk-pixbuf-2.0-0 libffi-dev \
    shared-mime-info fonts-noto-cjk fontconfig \
    chromium xvfb xauth libnss3 libxss1 libatk-bridge2.0-0 libgtk-3-0 libgbm1 libasound2 && \
    rm -rf /var/lib/apt/lists/*

# Layer 2: Node.js 22 (mongodb-mcp-server requires Node >= 22.13)
RUN curl -fsSL https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-x64.tar.gz -o /tmp/node.tar.gz && \
    tar -xzf /tmp/node.tar.gz -C /usr/local --strip-components=1 --no-same-owner && \
    rm /tmp/node.tar.gz

# Layer 3: python deps (changes occasionally)
RUN pip install --no-cache-dir \
    numpy pandas scipy sympy \
    matplotlib seaborn plotly kaleido \
    scikit-learn statsmodels \
    pillow openpyxl nodriver weasyprint

# npm-installed node-gyp bundles its own gyp; replace Debian's hardcoded path
RUN npm install -g node-gyp && \
    mkdir -p /usr/share/nodejs && \
    rm -rf /usr/share/nodejs/node-gyp && \
    ln -s /usr/local/lib/node_modules/node-gyp /usr/share/nodejs/node-gyp

# Layer 4: OpenClaw Channel Gateway CLI
ARG OCG_VERSION=1.0.14
ARG OCG_CACHE_BUST=0
RUN echo "Installing openclaw-channel-gateway@${OCG_VERSION} cache-bust=${OCG_CACHE_BUST}" && \
    npm install -g openclaw-channel-gateway@${OCG_VERSION} && \
    find /usr/local/lib/node_modules/openclaw-channel-gateway -path '*/node_modules/.bin/*' -type f -exec chmod 755 {} \; && \
    find /usr/local/lib/node_modules/openclaw-channel-gateway -name node-gyp-build -type f -exec chmod 755 {} \;

# Layer 5: gh CLI (version bumps only)
RUN curl -fsSL https://github.com/cli/cli/releases/download/v2.92.0/gh_2.92.0_linux_amd64.tar.gz -o /tmp/gh.tar.gz && \
    tar xzf /tmp/gh.tar.gz -C /tmp && \
    mv /tmp/gh_2.92.0_linux_amd64/bin/gh /usr/local/bin/gh && \
    chmod +x /usr/local/bin/gh && \
    rm -rf /tmp/gh*

COPY --from=builder /core-ai-sandbox-runtime /usr/local/bin/core-ai-sandbox-runtime

# python:3.12-slim has pyvenv.cfg which makes pip refuse --user installs
RUN rm -f /usr/local/pyvenv.cfg

# npm runs at runtime via npx; suppress harmless fchown/fund/audit WARNs
ENV NPM_CONFIG_LOGLEVEL=error
ENV NPM_CONFIG_UPDATE_NOTIFIER=false
ENV NPM_CONFIG_FUND=false
ENV NPM_CONFIG_AUDIT=false
ENV NPM_CONFIG_PREFER_IPV4=true
ENV NPM_CONFIG_TIMEOUT=180000
ENV NPM_CONFIG_FETCH_TIMEOUT=300000
ENV NPM_CONFIG_FETCH_RETRIES=3

# xvfb display for non-headless browser automation
ENV DISPLAY=:99

RUN mkdir -p /root/ocg-work

WORKDIR /root/ocg-work
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/core-ai-sandbox-runtime"]
