# ==========================================
# REACT FRONTEND - PRODUCTION BUILD
# ==========================================
# Multi-stage build for optimized production image

# Stage 1: Build the React application
FROM node:18-alpine AS builder

WORKDIR /app

# Copy package files first for better Docker layer caching
COPY log-viewer/package*.json ./

# Install dependencies
# Using npm install (works without package-lock.json)
RUN npm install --silent

# Copy source code
COPY log-viewer/ ./

# Build the production bundle
RUN npm run build

# Stage 2: Serve with nginx (lightweight production server)
FROM nginx:alpine

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built React assets from builder stage
COPY --from=builder /app/build /usr/share/nginx/html

# Expose port 80
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# Start nginx in foreground
CMD ["nginx", "-g", "daemon off;"]