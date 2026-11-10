# FingertipsMCP for Chrome with Google Chrome on Xvfb. Build from the repository root after `./gradlew :chrome:fatJar`:
#
#   docker build -f chrome/docker/Dockerfile -t fingertipsmcp-chrome chrome
#
# Google publishes google-chrome-stable for amd64 and arm64, so the image builds for both:
#
#   docker buildx build --platform linux/amd64,linux/arm64 -f chrome/docker/Dockerfile chrome

# A Java runtime with only the modules `jdeps` lists for the jar, less java.desktop, which only JNA's
# Windows code uses. It is a fifth the size of the JRE package. Debian's jlink strips native debug
# symbols with binutils' objcopy, but copies the jmods' separate .debuginfo files, 630 MB of them,
# unless told to leave them out.
FROM debian:trixie-slim AS java

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-25-jdk-headless binutils \
    && jlink --add-modules java.base,java.logging,java.management \
        --strip-debug --exclude-files='**.debuginfo' --no-man-pages --no-header-files --output /opt/java

FROM debian:trixie-slim

ARG DEBIAN_FRONTEND=noninteractive

# Chrome needs libgbm, which pulls in Mesa's drivers and the LLVM they link, about 200 MB. Only GPU
# rendering and Xvfb's GLX load them, so leave their files out. Under Xvfb, Chrome has no WebGL with
# or without them.
RUN printf 'path-exclude=%s\n' \
        '/usr/lib/*/libgallium-*.so' \
        '/usr/lib/*/libLLVM.so.*' \
        '/usr/lib/*/libz3.so.*' \
        '/usr/lib/*/libGLX_mesa.so.*' \
        '/usr/lib/*/dri/*' \
        >/etc/dpkg/dpkg.cfg.d/fingertips-no-mesa

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        google-chrome-stable \
        xvfb \
        xauth \
        tzdata \
        fonts-liberation \
        fonts-dejavu-core \
        fonts-noto-core \
        fonts-noto-cjk \
        fonts-noto-color-emoji \
    && apt-get purge -y gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Chrome refuses its sandbox as root, and --no-sandbox is a tell, so run as a user.
# The volumes' mount points exist here, owned by that user, so a new named volume takes their owner.
# Docker makes a mount point the image lacks, and its parents, owned by root, which the user can't write.
RUN useradd --create-home --uid 1000 --shell /usr/sbin/nologin fingertips \
    && install -d -o fingertips -g fingertips \
        /home/fingertips/.config \
        /home/fingertips/.config/fingertips-chrome \
        /home/fingertips/.local \
        /home/fingertips/.local/share \
        /home/fingertips/.local/share/fingertips-chrome

COPY --from=java /opt/java /opt/java
COPY build/dist/fingertipsmcp-chrome.jar /opt/fingertips-chrome/fingertipsmcp-chrome.jar
COPY docker/entrypoint.sh /usr/local/bin/fingertips-chrome-entrypoint

USER fingertips
WORKDIR /home/fingertips
ENV HOME=/home/fingertips \
    PATH=/opt/java/bin:$PATH \
    FINGERTIPS_PORT=47100 \
    FINGERTIPS_SCREEN=1920x1080x24

# Settings, tokens, and pairing.json; and the Chrome profile.
VOLUME ["/home/fingertips/.config/fingertips-chrome", "/home/fingertips/.local/share/fingertips-chrome"]
EXPOSE 47100

ENTRYPOINT ["/usr/local/bin/fingertips-chrome-entrypoint"]
