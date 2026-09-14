# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# El pom raiz declara los 5 modulos; Maven exige que TODOS existan para armar el
# reactor, aunque -pl solo construya uno. Por eso se copian todos los pom.xml
# (livianos y estables: mejoran el cache de capas) y solo el codigo de orders.
COPY pom.xml .
COPY ms-pedidos360-bff/pom.xml ms-pedidos360-bff/pom.xml
COPY ms-pedidos360-orders/pom.xml ms-pedidos360-orders/pom.xml
COPY ms-pedidos360-catalog/pom.xml ms-pedidos360-catalog/pom.xml
COPY ms-pedidos360-notify/pom.xml ms-pedidos360-notify/pom.xml
COPY ms-pedidos360-audit/pom.xml ms-pedidos360-audit/pom.xml

# El pom raiz exige un toolchain JDK 21 (maven-toolchains-plugin). Se declara
# apuntando al JDK de la propia imagen, que ya es 21.
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n  <toolchain>\n    <type>jdk</type>\n    <provides><version>21</version></provides>\n    <configuration><jdkHome>%s</jdkHome></configuration>\n  </toolchain>\n</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml

COPY ms-pedidos360-orders ms-pedidos360-orders

RUN mvn -q -pl ms-pedidos360-orders -am clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/ms-pedidos360-orders/target/*.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=dev
ENTRYPOINT ["java", "-jar", "app.jar"]
