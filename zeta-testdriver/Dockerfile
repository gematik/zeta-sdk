ARG DOCKER_HUB="docker.io"

FROM  $DOCKER_HUB/eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# The package "pebble" is not actually installed, but the binary is there and triggers
# a trivy finding. As we don't use it, we just remove it (as it's not installed apt-get remove pebble won't work)
RUN rm -f /usr/bin/pebble

WORKDIR /app

COPY zeta-testdriver/build/libs/zeta-testdriver.jar /app/zeta-testdriver.jar
COPY zeta-testdriver/build/runtime-libs/ /app/lib/

EXPOSE 8080

# CVE-2026-114938 (Java4Shell): disable vulnerable Base64 intrinsic until base image has a fixed JRE (>= 21.0.13.1)
ENTRYPOINT ["java","-XX:-UseBASE64Intrinsics","-cp","/app/zeta-testdriver.jar:/app/lib/*","de.gematik.zeta.driver.MainKt"]
