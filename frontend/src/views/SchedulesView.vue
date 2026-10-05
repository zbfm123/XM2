<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, newIdempotencyKey } from '../api'

/**
 * 医生号源列表 + 挂号（T-015 展示 / T-016 挂号）。
 *
 * 对应接口：
 *   GET  /api/schedules?doctorId=&page=&size=
 *   POST /api/appointments  {scheduleId, idempotencyKey}
 */

const route = useRoute()
const router = useRouter()
const doctorId = Number(route.params.doctorId)

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const totalPages = ref(0)
const loading = ref(true)
const error = ref('')

/**
 * 幂等键的保管处：scheduleId → key。
 *
 * ⚠️ 这是本页最容易写错的地方，值得说清楚：
 *
 * 幂等的前提是"**同一次提交意图复用同一个键**"。所以键不能每次点击都新生成——
 * 那样每次点击都是"新的一次提交"，后端会当成两笔不同的挂号。
 *
 * 这里按排班 id 复用：**同一排班的第二次提交会带同一个键**，
 * 后端因此能识别出"这是重试"，直接返回第一次那张单（响应里 replayed=true）。
 *
 * 实践中的三重防护：
 *   ① 前端：按钮 loading 期间禁用（挡住绝大多数双击）
 *   ② 前端：同一排班复用同一个键（挡住"请求发出去了但响应没回来，用户又点一次"）
 *   ③ 后端：idempotency_key 唯一索引（挡住前两层都被绕过的情况）
 *
 * 演示时可以这样讲：**前端这两层是体验，后端那一层才是保证。**
 */
const idempotencyKeys = reactive({})

const bookingId = ref(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.schedules(doctorId, { page: page.value, size: size.value })
    items.value = res.items
    total.value = res.total
    totalPages.value = res.totalPages
  } catch (e) {
    error.value = e.friendlyMessage || '加载号源失败'
  } finally {
    loading.value = false
  }
}

function changePage(p) {
  page.value = p
  load()
}

function periodLabel(period) {
  if (period === 'AM') return '上午'
  if (period === 'PM') return '下午'
  return period
}

async function book(row) {
  // 同一排班复用同一个键；没有才生成
  if (!idempotencyKeys[row.id]) {
    idempotencyKeys[row.id] = newIdempotencyKey()
  }

  bookingId.value = row.id
  try {
    const res = await api.book(row.id, idempotencyKeys[row.id])

    // ⚠️ replayed=true 表示"这个订单早就存在，我没新建"。
    //    这不常见，但演示时正好可以讲幂等：同一次提交重复到达时，
    //    用户拿到的还是**他自己那一单**，而不是一条"重复提交"的报错。
    if (res.replayed) {
      ElMessage.info(`该挂号此前已提交，单号 ${res.appointmentNo}`)
    } else {
      ElMessage.success(`挂号成功，单号 ${res.appointmentNo}`)
    }

    // 挂号成功后清掉这个键：这次提交意图已经完成，
    // 用户若想再挂同一个排班，那应该是"新的一次意图"。
    delete idempotencyKeys[row.id]

    // 号源数变了，刷新当前页
    await load()
  } catch (e) {
    // 号源约满、已挂过同一排班等，后端都给了明确错误码与中文提示：
    //   NO_SLOTS_AVAILABLE / ALREADY_BOOKED
    // 直接展示它，比前端自己猜一句话更准确。
    ElMessage.error(e.friendlyMessage || '挂号失败')
    await load()
  } finally {
    bookingId.value = null
  }
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-button size="small" @click="router.back()">← 返回</el-button>
      <h2 class="page-title" style="margin: 0">选择号源</h2>
      <span class="muted" style="margin-left: auto">共 {{ total }} 个号源</span>
    </div>

    <div v-if="error" class="hint hint-err">{{ error }}</div>

    <div v-loading="loading">
      <el-empty
        v-if="!loading && !error && items.length === 0"
        description="该医生暂无排班"
      />

      <el-table v-if="items.length" :data="items" border style="width: 100%">
        <el-table-column label="就诊日期" width="130">
          <template #default="{ row }">{{ row.workDate }}</template>
        </el-table-column>
        <el-table-column label="时段" width="90">
          <template #default="{ row }">{{ periodLabel(row.period) }}</template>
        </el-table-column>
        <el-table-column label="剩余 / 总号源" width="140">
          <template #default="{ row }">
            <!-- 已约满时用灰色显示，与下面禁用的按钮呼应 -->
            <span :class="{ 'sold-out': row.soldOut }">
              {{ row.remainingSlots }} / {{ row.totalSlots }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="挂号费" width="100">
          <template #default="{ row }">¥{{ row.fee }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <!--
              ⚠️ 号源为 0 时**按钮直接禁用并显示"已约满"**，让用户提前知道，
              而不是点下去才收到一个错误。
              这是 T-016 明确要求的行为，靠后端返回的 soldOut 字段实现
              （判断逻辑在服务端，前端不重复实现一遍"什么算约满"）。
            -->
            <el-button
              v-if="!row.soldOut"
              type="primary"
              size="small"
              :loading="bookingId === row.id"
              @click="book(row)"
            >
              挂号
            </el-button>
            <el-button v-else size="small" disabled>已约满</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="totalPages > 1" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.pager {
    margin-top: 1rem;
    display: flex;
    justify-content: center;
}
</style>
