# MezzoSopranoClef — headless, GPU-free Minecraft bot in a container.
#
# Build:  docker build -t mezzosopranoclef .
# Run:    docker run --rm -it -p 8731:8731 -v clef-data:/data \
#                 -e CLEF_OPTS="-Dmezzoclef.ws.host=0.0.0.0" mezzosopranoclef
#
# Minecraft itself is NOT baked into the image (it isn't redistributable). It downloads into the
# /data volume on first run, exactly like the launcher does on bare metal. Mount a volume so the
# download (and the bot's config/auth cache) persist across runs.

# Pinned to linux/amd64: Mojang ships LWJGL natives for x86_64 (and macOS), but NOT linux-arm64,
# so the game can't start on arm64 Linux. On an Apple Silicon host this image runs under emulation
# (fine for a sim-bound bot); on a normal amd64 server it runs natively.

# --- build stage: produce the launcher jar. Loom downloads MC here only to remap the mod; this
#     whole stage (and the cached MC) is discarded — the final image carries no Minecraft. ---
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :launcher:jar --no-daemon \
    && cp launcher/build/libs/mezzosopranoclef-launcher-*.jar /launcher.jar

# --- runtime stage: a JRE + the self-bootstrapping launcher. ---
FROM --platform=linux/amd64 eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="MezzoSopranoClef" \
      org.opencontainers.image.description="Headless, GPU-free Minecraft bot (Fabric 1.21.8)"
WORKDIR /bot
COPY --from=build /launcher.jar /bot/launcher.jar

# Bot data, Minecraft download, config + auth cache live here — mount a volume to persist them.
ENV CLEF_GAMEDIR=/data
# Heap cap + worker-thread cap suit a no-render bot; override per container as needed.
ENV CLEF_MAX_HEAP=768m
ENV CLEF_BG_THREADS=4
VOLUME ["/data"]

# Control plane (8731) and optional web dashboard (8732). Bind to 0.0.0.0 via CLEF_OPTS to reach
# them from the host; the control plane is token-protected by default (see /data/config).
EXPOSE 8731 8732

ENTRYPOINT ["java", "-jar", "/bot/launcher.jar"]
