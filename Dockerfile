# --- Etapa 1: Build (Construcción) ---
# Usamos una imagen JDK 17 (basada en tu pom.xml) para compilar el proyecto
FROM eclipse-temurin:17-jdk AS builder

# Establecemos el directorio de trabajo
WORKDIR /app

# Copiamos primero los archivos de build (Maven Wrapper)
COPY .mvn/ .mvn
COPY mvnw pom.xml ./

# Hacemos el wrapper ejecutable
RUN chmod +x ./mvnw

# (Opcional pero recomendado) Descargamos dependencias para cache
# Esto acelera builds futuros si el pom.xml no cambia
RUN ./mvnw dependency:go-offline

# Copiamos el resto del código fuente
COPY src ./src

# Compilamos el proyecto y creamos el .jar, saltando los tests
# Usamos 'clean package' para asegurar una build limpia
RUN ./mvnw clean package -DskipTests

# --- Etapa 2: Runtime (Ejecución) ---
# Usamos una imagen JRE 17 mínima (Alpine) para correr la app
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# El nombre del JAR debe coincidir con el <artifactId> y <version> de tu pom.xml
ARG JAR_FILE=target/medicamentos_backend-0.0.1-SNAPSHOT.jar

# Copiamos solo el .jar compilado desde la etapa 'builder' y lo renombramos
COPY --from=builder /app/${JAR_FILE} app.jar

# Exponemos el puerto 8080 (el que usa tu application.properties)
# Render usará una variable para anular esto, lo cual es correcto.
EXPOSE 8080

# Comando para iniciar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
