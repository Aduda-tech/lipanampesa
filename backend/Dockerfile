FROM node:20-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
EXPOSE 3000
ENV DB_FILE=/data/shop.db
VOLUME /data
CMD ["node", "src/index.js"]
