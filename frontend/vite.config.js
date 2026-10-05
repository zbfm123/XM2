import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * 只需理解两件事（决策 D-08：只用最基础的能力）：
 *
 * 1. **开发服务器与生产是两条路径**
 *    - 开发（`npm.cmd run dev`，端口 5173）：用下面的 `proxy` 把 /api 转发给后端 8081，
 *      这样浏览器眼里前后端同源，**开发期完全不涉及跨域**；
 *    - 生产（Nginx）：由 `deploy/nginx/nginx.conf` 做同样的事。
 *    两处转发的路径规则必须一致，否则会出现"开发能跑、上线 404"。
 *
 * 2. **前端代码里永远写 `/api/xxx`，不写 `http://localhost:8081/api/xxx`**
 *    写死主机名会让"开发/生产"必须改代码。相对路径则由环境去解决——
 *    这也正是 Nginx 那一层存在的意义。
 */
export default defineConfig({
  plugins: [vue()],

  server: {
    port: 5173,
    // 后端不在时不要自动开别的端口：端口变了会让人以为配置没生效
    strictPort: true,
    proxy: {
      // ⚠️ 与 deploy/nginx/nginx.conf 的 location /api/ 保持一致的语义：
      //    原样转发完整路径（不 rewrite），后端收到 /api/auth/login。
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true
      }
    }
  },

  build: {
    // 产物目录固定为 dist —— Nginx 的 root 就指向它
    outDir: 'dist',
    // 构建前清空目录，避免旧文件残留（曾出现"改了代码但页面没变"）
    emptyOutDir: true
  }
})
