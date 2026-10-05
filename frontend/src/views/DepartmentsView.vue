<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

/**
 * 科室列表（T-015）。
 *
 * 对应接口 `GET /api/departments`，返回的是一个**数组**（字典数据不分页），
 * 所以这里可以直接赋值给 list，不用解一层 items。
 */

const router = useRouter()

const list = ref([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    list.value = await api.departments()
  } catch (e) {
    error.value = e.friendlyMessage || '加载科室失败'
  } finally {
    loading.value = false
  }
})

function goDoctors(dept) {
  router.push({ name: 'doctors', params: { deptId: dept.id } })
}
</script>

<template>
  <div class="page">
    <h2 class="page-title">请选择科室</h2>

    <div v-if="error" class="hint hint-err">{{ error }}</div>

    <div v-loading="loading">
      <el-empty v-if="!loading && list.length === 0" description="暂无科室数据" />

      <div class="dept-grid">
        <div
          v-for="d in list"
          :key="d.id"
          class="dept-card"
          @click="goDoctors(d)"
        >
          <div class="dept-name">{{ d.name }}</div>
          <div class="dept-desc muted">{{ d.description || '—' }}</div>
          <div class="dept-code muted">{{ d.code }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dept-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 1rem;
}

.dept-card {
    background: #fff;
    border: 1px solid #e5e7eb;
    border-radius: 8px;
    padding: 1.1rem;
    cursor: pointer;
    transition: all 0.15s;
}

.dept-card:hover {
    border-color: #a5b4fc;
    box-shadow: 0 2px 10px rgba(67, 56, 202, 0.08);
    transform: translateY(-1px);
}

.dept-name {
    font-size: 1.05rem;
    font-weight: 600;
    margin-bottom: 0.35rem;
}

.dept-desc {
    min-height: 2.4em;
}

.dept-code {
    margin-top: 0.5rem;
    font-size: 0.8rem;
    font-family: monospace;
}
</style>
