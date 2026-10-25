# The AIsolate chat, a demo AIsolate client; the chart installs it as platform.chat (headless or not).
# Build context is the repository root (the npm workspace). NEXT_PUBLIC_* are inlined at build time.
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json tsconfig.base.json ./
COPY libs/ui/package.json libs/ui/
COPY apps/chat/package.json apps/chat/
RUN npm ci
COPY libs/ui libs/ui
COPY apps/chat apps/chat
ARG NEXT_PUBLIC_AISOLATE_URL
ARG NEXT_PUBLIC_AISOLATE_AIRLOCK_ID=
ENV NEXT_PUBLIC_AISOLATE_URL=$NEXT_PUBLIC_AISOLATE_URL \
    NEXT_PUBLIC_AISOLATE_AIRLOCK_ID=$NEXT_PUBLIC_AISOLATE_AIRLOCK_ID \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build -w apps/chat

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
COPY --from=build /src/apps/chat/.next/standalone ./
COPY --from=build /src/apps/chat/.next/static ./apps/chat/.next/static
COPY --from=build /src/apps/chat/public ./apps/chat/public
RUN rm -rf node_modules/sharp node_modules/@img
USER node
EXPOSE 3000
CMD ["node", "apps/chat/server.js"]
