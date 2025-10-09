FROM eclipse-temurin:21-jre
WORKDIR /app

COPY zeta-testdriver/build/libs/zeta-testdriver.jar /app/zeta-testdriver.jar
COPY zeta-testdriver/build/runtime-libs/ /app/lib/

EXPOSE 8080
ENTRYPOINT ["java","-cp","/app/zeta-testdriver.jar:/app/lib/*","de.gematik.zeta.driver.MainKt"]
