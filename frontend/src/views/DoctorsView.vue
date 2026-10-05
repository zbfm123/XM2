<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'

/**
 * 科室下的医生列表（T-015）。
 *
 * 对应接口 `GET /api/doctors?deptId=`。
 *
 * ⚠️ 这里要处理后端刻意做出的一个区分：
 *    科室**不存在** → 404（NOT_FOUND），说明"你进错页面了"；
 *    科室存在但没医生 → 200 + 空列表，说明"这个科室暂时没医生"。
 * 两者的用户下一步不同，所以界面上也要分开表达（错误条 vs 空状态）。
 */

const route = useRoute()
const router = useRouter()

// ⚠️ 路由参数是**字符串**，这里要转成数字再传给后端。
//    不转的话 URL 会变成 ?deptId=1（碰巧也对），但一旦参数名或类型校验收紧就会踩坑；
//    显式转数字是让"这是个 id"这件事在代码里可见。
const deptId = Number(route.params.deptId)

const list = ref([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    list.value = await api.doctors(deptId)
  } catch (e) {
    // 404 在这里是"科室不存在"，用后端给的 message 比自造一句更准
    error.value = e.friendlyMessage || '加载医生失败'
  } finally {
    loading.value = false
  }
})

function goSchedules(doctor) {
  router.push({ name: 'schedules', params: { doctorId: doctor.id } })
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-button size="small" @click="router.push({ name: 'departments' })">
        ← 返回科室
      </el-button>
      <h2 class="page-title" style="margin: 0">请选择医生</h2>
    </div>

    <div v-if="error" class="hint hint-err">{{ error }}</div>

    <div v-loading="loading">
      <!-- 科室存在但没有医生：这是正常状态，不是错误 -->
      <el-empty
        v-if="!loading && !error && list.length === 0"
        description="该科室暂无出诊医生"
      />

      <el-table v-if="list.length" :data="list" border style="width: 100%">
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="title" label="职称" width="140" />
        <el-table-column prop="specialty" label="擅长" min-width="200" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="goSchedules(row)">
              查看号源
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
