import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

// 路由：/ 前台展示（公开）；/admin 后台管理（密码验证在工作台内完成）
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    {
      path: '/admin',
      name: 'admin',
      // 后台页按需加载（懒加载），减小首屏体积
      component: () => import('../views/AdminView.vue'),
    },
    {
      path: '/sign/:token',
      name: 'sign',
      // 乙方签署页：公开访问，凭链接中的 token 校验合同
      component: () => import('../views/SignView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
