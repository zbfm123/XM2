import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './style.css'

/**
 * 应用入口。
 *
 * Element Plus 是**全量引入**的（而不是按需引入）：
 * 按需引入需要额外的 unplugin 配置，而本项目的取舍是"能跑通 > 用全"（决策 D-08）。
 * 代价是打包体积略大——对本地演示项目，这个代价可以接受，
 * 而"多一个构建插件就可能多一个卡住的地方"才是真风险。
 */
const app = createApp(App)

app.use(ElementPlus)
app.use(router)
app.mount('#app')
