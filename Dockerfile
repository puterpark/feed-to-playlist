FROM amazoncorretto:21-alpine

WORKDIR /app

RUN mkdir logs

COPY ./build/libs/feed-to-playlist.jar /app/feed-to-playlist.jar

ENV TZ=Asia/Seoul

VOLUME /app/logs
EXPOSE 8080

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "feed-to-playlist.jar"]
