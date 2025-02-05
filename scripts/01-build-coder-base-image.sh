#!/bin/bash -e

VERSION="beta-02"
docker login -u "$DOCKER_HUB_USERNAME" -p "$DOCKER_HUB_ACCEDD_TOKEN"

docker build --platform linux/amd64 -t demobin/coder-base:"${VERSION}" -t demobin/coder-base:latest coder-base/
docker push demobin/coder-base:latest