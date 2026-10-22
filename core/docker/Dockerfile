# syntax=docker/dockerfile:1-labs
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
FROM ghcr.io/airlift/jvmkill:latest AS jvmkill

# Download and extract JDK using a build platform to avoid tar extract issue while running in qemu
FROM --platform=$BUILDPLATFORM redhat/ubi10-minimal:latest AS jdk-download
ARG JDK_DOWNLOAD_LINK
ARG JDK_VERSION
ENV JAVA_HOME="/usr/lib/jvm/${JDK_VERSION}"

RUN \
    set -xeuo pipefail && \
    microdnf install -y tar gzip && \
    # Install JDK from the provided archive link \
    echo "Downloading JDK from ${JDK_DOWNLOAD_LINK}" && \
    mkdir -p "${JAVA_HOME}" && \
    curl -#LfS --retry 8 "${JDK_DOWNLOAD_LINK}" -o /jdktarfile && \
    tar -zxf /jdktarfile --strip 1 -C "${JAVA_HOME}" && \
    echo "Done"

# The native launcher in the server tarball is airlift/launcher's release binary,
# so it carries whichever Go airlift built it with (go1.26.5 in launcher 321,
# GO-2026-6809). Rebuild the same source with a current Go: the revision and
# version are read from the bundled binary's own build info, so this follows
# every launcher bump without a version to keep in step. Flags as in airlift's
# src/main/go/build.sh.
FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS launcher
ARG ARCH
COPY trino-server/bin/linux-${ARCH}/launcher /bundled-launcher
RUN \
    set -eu && \
    info=$(go version -m /bundled-launcher) && \
    revision=$(echo "$info" | sed -n 's/.*vcs\.revision=\([0-9a-f]*\).*/\1/p') && \
    version=$(echo "$info" | sed -n 's/.*-X launcher\/args\.Version=\([^ "]*\).*/\1/p') && \
    : "${revision:?no vcs.revision in the bundled launcher}" "${version:?no args.Version in the bundled launcher}" && \
    apk add --no-cache git && \
    git init -q /src && \
    git -C /src fetch -q --depth 1 https://github.com/airlift/launcher "${revision}" && \
    git -C /src checkout -q FETCH_HEAD && \
    cd /src/src/main/go && \
    CGO_ENABLED=0 GOOS=linux GOARCH="${ARCH}" go build \
      -ldflags="-s -w -extldflags=-static -X launcher/args.Version=${version}" -o /launcher . && \
    go version -m /launcher

FROM redhat/ubi10 AS packages

RUN \
    set -xeuo pipefail && \
    mkdir -p /tmp/overlay/usr/libexec/ && \
    touch /tmp/overlay/usr/libexec/grepconf.sh && \
    chmod +x /tmp/overlay/usr/libexec/grepconf.sh && \
    dnf update -y --nobest --allowerasing && \
    dnf install --installroot /tmp/overlay --setopt install_weak_deps=false --nodocs -y \
      less \
      libstdc++ `# required by snappy and duckdb` \
      curl-minimal grep `# required by health-check` \
      zlib `#required by java` \
      shadow-utils `# required by useradd` \
      nss_wrapper `# required by run-trino to fake passwd entry on OpenShift` \
      tar `# required to support kubectl cp` && \
      rm -rf /tmp/overlay/var/cache/*

# Use ubi10 micro as it's more secure
FROM redhat/ubi10-micro:latest
ARG JDK_VERSION
ARG ARCH
ENV JAVA_HOME="/usr/lib/jvm/${JDK_VERSION}"
ENV PATH=$PATH:$JAVA_HOME/bin
ENV LANG=C.UTF-8
ENV CATALOG_MANAGEMENT=static
COPY --from=jdk-download $JAVA_HOME $JAVA_HOME
COPY --from=packages /tmp/overlay /

RUN \
    set -xeu && \
    groupadd trino --gid 1000 && \
    useradd trino --uid 1000 --gid 1000 --create-home && \
    mkdir -p /usr/lib/trino /data/trino && \
    chown -R "trino:0" /usr/lib/trino /data/trino && \
    chmod -R g=u /usr/lib/trino /data/trino

COPY --chown=trino:trino trino-cli.jar /usr/bin/trino
COPY --chown=trino:trino --exclude=bin/darwin-* --exclude=bin/linux-* trino-server /usr/lib/trino
COPY --chown=trino:trino trino-server/bin/linux-${ARCH} /usr/lib/trino/bin/linux-${ARCH}
COPY --chown=trino:trino --from=launcher /launcher /usr/lib/trino/bin/linux-${ARCH}/launcher
COPY --chown=trino:trino default/etc /etc/trino
COPY --chown=trino:trino --from=jvmkill /libjvmkill.so /usr/lib/trino/bin

EXPOSE 8080
USER trino:trino
CMD ["/usr/lib/trino/bin/run-trino"]
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s \
  CMD /usr/lib/trino/bin/health-check
