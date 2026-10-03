FROM node:22-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY server.js admin.html ./

ENV PORT=9500
EXPOSE 9500

CMD ["node", "server.js"]
