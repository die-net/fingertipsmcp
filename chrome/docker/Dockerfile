# FingertipsMCP for Chrome with Google Chrome on Xvfb. Build from the repository root after `./gradlew :chrome:fatJar`:
#
#   docker build -f chrome/docker/Dockerfile -t fingertipsmcp-chrome chrome
#
# Google publishes google-chrome-stable for amd64 and arm64, so the image builds for both:
#
#   docker buildx build --platform linux/amd64,linux/arm64 -f chrome/docker/Dockerfile chrome
FROM debian:trixie-slim

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        google-chrome-stable \
        openjdk-21-jre-headless \
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
RUN useradd --create-home --uid 1000 --shell /usr/sbin/nologin fingertips

COPY build/dist/fingertipsmcp-chrome.jar /opt/fingertips-chrome/fingertipsmcp-chrome.jar
COPY docker/entrypoint.sh /usr/local/bin/fingertips-chrome-entrypoint

USER fingertips
WORKDIR /home/fingertips
ENV HOME=/home/fingertips \
    FINGERTIPS_PORT=47100 \
    FINGERTIPS_SCREEN=1920x1080x24

# Settings, tokens, and pairing.json; and the Chrome profile.
VOLUME ["/home/fingertips/.config/fingertips-chrome", "/home/fingertips/.local/share/fingertips-chrome"]
EXPOSE 47100

ENTRYPOINT ["/usr/local/bin/fingertips-chrome-entrypoint"]
