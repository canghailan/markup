FROM maven:3-jdk-8 AS build-stage

WORKDIR /workspace/

COPY pom.xml .
RUN mvn verify clean --fail-never

COPY . .
RUN mvn package -Dmaven.test.skip=true


FROM openjdk:8-jdk

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app/

ENV JAVA_OPTS=-Xmx128m
ENV MARKUP_GIT=
ENV MARKUP_PORT=80

COPY --from=build-stage /workspace/target/*-with-dependencies.jar /app/app.jar

CMD java -jar $JAVA_OPTS /app/app.jar

