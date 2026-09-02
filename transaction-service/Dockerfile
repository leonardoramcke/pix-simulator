# --- Etapa 1: build ---
# Compila o projeto dentro de um container com JDK completo, sem exigir
# Java/Maven instalados na máquina de quem for rodar o projeto.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copia primeiro só o wrapper e o pom.xml para aproveitar cache de camadas
# do Docker: se o código-fonte mudar mas as dependências não, o Docker
# reusa a camada de download de dependências em vez de baixar tudo de novo.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# --- Etapa 2: runtime ---
# Imagem final enxuta: só o JRE (não o JDK completo) e o .jar já compilado.
# Reduz o tamanho final da imagem em várias centenas de MB.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
