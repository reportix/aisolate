# The AIsolate UI. Build context is the repository root (the npm workspace with libs/ui).
# NEXT_PUBLIC_* are inlined at build time: one image per environment, built with its URLs.
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json tsconfig.base.json ./
COPY libs/ui/package.json libs/ui/
COPY apps/frontend/package.json apps/frontend/
RUN npm ci
COPY libs/ui libs/ui
COPY apps/frontend apps/frontend
ARG NEXT_PUBLIC_AISOLATE_URL
ARG NEXT_PUBLIC_OIDC_AUTHORITY
ARG NEXT_PUBLIC_OIDC_CLIENT_ID=aisolate-ui
ARG NEXT_PUBLIC_TRACE_URL=
ARG AISOLATE_VERSION=
ARG NEXT_PUBLIC_AISOLATE_SOURCE_URL=
ENV NEXT_PUBLIC_AISOLATE_URL=$NEXT_PUBLIC_AISOLATE_URL \
    NEXT_PUBLIC_OIDC_AUTHORITY=$NEXT_PUBLIC_OIDC_AUTHORITY \
    NEXT_PUBLIC_OIDC_CLIENT_ID=$NEXT_PUBLIC_OIDC_CLIENT_ID \
    NEXT_PUBLIC_TRACE_URL=$NEXT_PUBLIC_TRACE_URL \
    AISOLATE_VERSION=$AISOLATE_VERSION \
    NEXT_PUBLIC_AISOLATE_SOURCE_URL=$NEXT_PUBLIC_AISOLATE_SOURCE_URL \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build -w apps/frontend

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
COPY --from=build /src/apps/frontend/.next/standalone ./
COPY --from=build /src/apps/frontend/.next/static ./apps/frontend/.next/static
COPY --from=build /src/apps/frontend/public ./apps/frontend/public
RUN rm -rf node_modules/sharp node_modules/@img
USER node
EXPOSE 3000
CMD ["node", "apps/frontend/server.js"]
