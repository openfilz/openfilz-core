# Floating 25 JRE tag: a rebuild picks up the latest Temurin 25 patch release. The old pin
# (25_36-jdk-noble) was the September 2025 GA build and never received a security fix.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081
# -XX:-UseBASE64Intrinsics: CVE-2026-114938 ("Java4Shell") mitigation, see openfilz-api/pom.xml.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "app.jar"]
