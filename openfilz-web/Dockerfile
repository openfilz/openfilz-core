@ -1,8 +1,39 @@
# Multi-stage Dockerfile for OpenFilz Web UI
# Stage 1: Build the Angular application
FROM node:22-alpine AS build

WORKDIR /app

# Copy package files
COPY package.json package-lock.json ./

# Install dependencies
RUN npm ci --legacy-peer-deps

# Copy source code
COPY . .

# Build the application for production
RUN npm run build -- --configuration production

# Stage 2: Serve with Nginx
FROM nginxinc/nginx-unprivileged:alpine-perl
COPY dist/openfilz-ui/browser /usr/share/nginx/html

# Copy built assets from build stage
COPY --from=build /app/dist/openfilz-ui/browser /usr/share/nginx/html

# Copy nginx configuration
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

# Switch to nginx user
USER nginx

EXPOSE 80
# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/ || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
