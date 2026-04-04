FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew.bat build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew build --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/distributions/mcap-*.tar mcap.tar
RUN tar -xf mcap.tar --strip-components=1 && rm mcap.tar
VOLUME /app/data
ENV MCAP_DB=/app/data/mcap.db
ENV MCAP_PORT=7070
EXPOSE 7070
ENTRYPOINT ["bin/mcap"]
